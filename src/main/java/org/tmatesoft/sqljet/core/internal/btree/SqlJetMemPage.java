/**
 * SqlJetMemPage.java
 * Copyright (C) 2009-2013 TMate Software Ltd
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@sqljet.com
 */
package org.tmatesoft.sqljet.core.internal.btree;

import static org.tmatesoft.sqljet.core.internal.SqlJetUtility.memset;
import static org.tmatesoft.sqljet.core.internal.SqlJetUtility.put2byte;
import static org.tmatesoft.sqljet.core.internal.SqlJetUtility.put4byte;
import static org.tmatesoft.sqljet.core.internal.btree.SqlJetBtree.traceInt;

import org.tmatesoft.sqljet.core.SqlJetErrorCode;
import org.tmatesoft.sqljet.core.SqlJetException;
import org.tmatesoft.sqljet.core.internal.ISqlJetConfig;
import org.tmatesoft.sqljet.core.internal.ISqlJetLimits;
import org.tmatesoft.sqljet.core.internal.ISqlJetMemoryPointer;
import org.tmatesoft.sqljet.core.internal.ISqlJetPage;
import org.tmatesoft.sqljet.core.internal.SqlJetAssert;
import org.tmatesoft.sqljet.core.internal.SqlJetCloneable;
import org.tmatesoft.sqljet.core.internal.SqlJetUtility;
import org.tmatesoft.sqljet.core.internal.memory.SqlJetVarintResult;
import org.tmatesoft.sqljet.core.internal.memory.SqlJetVarintResult32;

/**
 * As each page of the file is loaded into memory, an instance of the following
 * structure is appended and initialized to zero. This structure stores
 * information about the page that is decoded from the raw file page.
 *
 * The pParent field points back to the parent page. This allows us to walk up
 * the BTree from any leaf to the root. Care must be taken to unref() the parent
 * page pointer when this page is no longer referenced. The pageDestructor()
 * routine handles that chore.
 *
 * Access to all fields of this structure is controlled by the mutex stored in
 * MemPage.pBt->mutex.
 *
 * @author TMate Software Ltd.
 * @author Sergey Scherbina (sergey.scherbina@gmail.com)
 *
 */
public class SqlJetMemPage extends SqlJetCloneable {

    /**
     * Page type flags. An ORed combination of these flags appear as the first
     * byte of on-disk image of every BTree page.
     */
    public static final byte PTF_INTKEY = 0x01;
    public static final byte PTF_ZERODATA = 0x02;
    public static final byte PTF_LEAFDATA = 0x04;
    public static final byte PTF_LEAF = 0x08;

    /** True if previously initialized. MUST BE FIRST! */
    protected boolean isInit;

    /** True if intkey flag is set */
    protected boolean intKey;

    /** True if leaf flag is set */
    protected boolean leaf;

    /** True if this page stores data */
    protected boolean hasData;

    protected int getHdrOffset() {
    	return (pgno == 1 ? 100 : 0);
    }

    /** Copy of BtShared.maxLocal or BtShared.maxLeaf */
    private int maxLocal;

    /** Copy of BtShared.minLocal or BtShared.minLeaf */
    protected int minLocal;

    /** Index in aData of first cell pointer */
    protected int cellOffset;

    /** Number of free bytes on the page */
    protected int nFree;

    /** Number of cells on this page, local and ovfl */
    protected int nCell;

    /** Mask for page offset */
    protected int maskPage;

    /** Cells that will not fit on aData[] */
//    protected List<SqlJetOvflCell> aOvfl = new LinkedList<>();
    protected SqlJetOvflCells aOvfl = new SqlJetOvflCells();

    /** Pointer to BtShared that this page is part of */
    protected SqlJetBtreeShared pBt;

    /** Pointer to disk image of the page data */
    protected ISqlJetMemoryPointer aData;

    /** Pager page handle */
    protected ISqlJetPage pDbPage;

    /** Page number for this page */
    protected int pgno = 0;

    /**
     * The ISAUTOVACUUM macro is used within balance_nonroot() to determine if
     * the database supports auto-vacuum or not. Because it is used within an
     * expression that is an argument to another macro (sqliteMallocRaw), it is
     * not possible to use conditional compilation. So, this macro is defined
     * instead.
     *
     * @return
     */
    private boolean isAutoVacuum() {
        return pBt.autoVacuum;
    }

    /**
     * Decode the flags byte (the first byte of the header) for a page and
     * initialize fields of the MemPage structure accordingly.
     *
     * Only the following combinations are supported. Anything different
     * indicates a corrupt database files:
     *
     * <p>
     * PTF_ZERODATA
     * </p>
     * <p>
     * PTF_ZERODATA | PTF_LEAF
     * </p>
     * <p>
     * PTF_LEAFDATA | PTF_INTKEY
     * </p>
     * <p>
     * PTF_LEAFDATA | PTF_INTKEY | PTF_LEAF
     * </p>
     */
    private void decodeFlags(int flagByte) throws SqlJetException {
        assert (pBt.mutex.held());
        leaf = (flagByte & PTF_LEAF) > 0;
        flagByte &= ~PTF_LEAF;
        if (flagByte == (PTF_LEAFDATA | PTF_INTKEY)) {
            intKey = true;
            hasData = leaf;
            maxLocal = pBt.maxLeaf;
            minLocal = pBt.minLeaf;
        } else if (flagByte == PTF_ZERODATA) {
            intKey = false;
            hasData = false;
            maxLocal = pBt.maxLocal;
            minLocal = pBt.minLocal;
        } else {
            throw new SqlJetException(SqlJetErrorCode.CORRUPT);
        }
    }

    /**
     * Initialize the auxiliary information for a disk block.
     *
     * Return SQLITE_OK on success. If we see that the page does not contain a
     * well-formed database page, then return SQLITE_CORRUPT. Note that a return
     * of SQLITE_OK does not guarantee that the page is well-formed. It only
     * shows that we failed to detect any corruption.
     */
    public void initPage() throws SqlJetException {

        assert (pBt != null);
        assert (pBt.mutex.held());
        assert (pgno == pDbPage.getPageNumber());
        assert (this == pDbPage.getExtra());
        assert (aData.getBuffer() == pDbPage.getData().getBuffer());

        if (!isInit) {
            int pc; /* Address of a freeblock within pPage->aData[] */
            int usableSize; /* Amount of usable space on each page */
            int nFree; /* Number of unused bytes on the page */
            int top; /* First byte of the cell content area */

            int hdr = getHdrOffset(); /* Offset to beginning of page header */
            decodeFlags(aData.getByteUnsigned(hdr));
            assert (pBt.pageSize >= 512 && pBt.pageSize <= 32768);
            maskPage = pBt.pageSize - 1;
            aOvfl.clear();
            usableSize = pBt.usableSize;
            this.cellOffset = hdr + 12 - 4 * (leaf ? 1 : 0);
            top = aData.getShortUnsigned(hdr + 5);
            nCell = aData.getShortUnsigned(hdr + 3);
            if (nCell > pBt.MX_CELL()) {
                /* To many cells for a single page. The page must be corrupt */
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            }
            int iCellFirst = cellOffset + 2*this.nCell;

            /* Compute the total free space on the page */
            pc = aData.getShortUnsigned(hdr + 1);
            nFree = aData.getByteUnsigned(hdr + 7) + top;// - (cellOffset + 2 * nCell);
            while (pc > 0) {
                int next, size;
                if (pc > usableSize - 4) {
                    /* Free block is off the page */
                    throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                }
                next = aData.getShortUnsigned(pc);
                size = aData.getShortUnsigned(pc + 2);
                if (next > 0 && next <= pc + size + 3) {
                    /* Free blocks must be in accending order */
                    throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                }
                nFree += size;
                pc = next;
            }
            if (nFree > usableSize) {
                /* Free space cannot exceed total page size */
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            }
            this.nFree = (nFree - iCellFirst);
            isInit = true;
        }
    }

    /**
     * Release a MemPage. This should be called once for each prior call to
     * sqlite3BtreeGetPage.
     *
     * @throws SqlJetException
     */
    public static void releasePage(SqlJetMemPage pPage) throws SqlJetException {
        if (pPage != null) {
            assert (pPage.aData != null);
            assert (pPage.pBt != null);
            assert (pPage.pDbPage.getExtra() == pPage);
            assert (pPage.pDbPage.getData().getBuffer() == pPage.aData.getBuffer());
            assert (pPage.pBt.mutex.held());
            pPage.pDbPage.unref();
        }
    }

    /**
     * Set the pointer-map entries for all children of page pPage. Also, if
     * pPage contains cells that point to overflow pages, set the pointer map
     * entries for the overflow pages as well.
     *
     * @throws SqlJetException
     */
    public void setChildPtrmaps() throws SqlJetException {
        int i; /* Counter variable */
        int nCell; /* Number of cells in page pPage */

        boolean isInitOrig = isInit;

        assert (pBt.mutex.held());
        try {
            initPage();
            nCell = this.nCell;

            for (i = 0; i < nCell; i++) {
                ISqlJetMemoryPointer pCell = findCell(i);

                ptrmapPutOvflPtr(pCell);

                if (!leaf) {
                    int childPgno = pCell.getInt();
                    pBt.ptrmapPut(childPgno, SqlJetPtrMapType.PTRMAP_BTREE, pgno);
                }
            }

            if (!leaf) {
                int childPgno = aData.getInt(getHdrOffset() + 8);
                pBt.ptrmapPut(childPgno, SqlJetPtrMapType.PTRMAP_BTREE, pgno);
            }
        } catch (SqlJetException e) {
            // set_child_ptrmaps_out:
            isInit = isInitOrig;
            throw e;
        }
    }

    /**
     * Somewhere on pPage, which is guarenteed to be a btree page, not an
     * overflow page, is a pointer to page iFrom. Modify this pointer so that it
     * points to iTo. Parameter eType describes the type of pointer to be
     * modified, as follows:
     *
     * PTRMAP_BTREE: pPage is a btree-page. The pointer points at a child page
     * of pPage.
     *
     * PTRMAP_OVERFLOW1: pPage is a btree-page. The pointer points at an
     * overflow page pointed to by one of the cells on pPage.
     *
     * PTRMAP_OVERFLOW2: pPage is an overflow-page. The pointer points at the
     * next overflow page in the list.
     *
     * @throws SqlJetExceptionRemove
     */
    public void modifyPagePointer(int iFrom, int iTo, SqlJetPtrMapType s) throws SqlJetException {
        assert (pBt.mutex.held());
        if (s == SqlJetPtrMapType.PTRMAP_OVERFLOW2) {
            /* The pointer is always the first 4 bytes of the page in this case. */
            if (aData.getInt() != iFrom) {
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            }
            put4byte(aData, iTo);
        } else {
            boolean isInitOrig = this.isInit;
            int i;
            int nCell;

            initPage();
            nCell = this.nCell;

            for (i = 0; i < nCell; i++) {
                ISqlJetMemoryPointer pCell = findCell(i);
                if (s == SqlJetPtrMapType.PTRMAP_OVERFLOW1) {
                    SqlJetBtreeCellInfo info;
                    info = parseCellPtr(pCell);
                    if (info.iOverflow > 0) {
                        if (iFrom == pCell.getInt(info.iOverflow)) {
                            put4byte(pCell, info.iOverflow, iTo);
                            break;
                        }
                    }
                } else {
                    if (pCell.getInt() == iFrom) {
                        put4byte(pCell, iTo);
                        break;
                    }
                }
            }

            if (i == nCell) {
                if (s != SqlJetPtrMapType.PTRMAP_BTREE || aData.getInt(getHdrOffset() + 8) != iFrom) {
                    throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                }
                put4byte(aData, getHdrOffset() + 8, iTo);
            }

            this.isInit = isInitOrig;
        }
    }

    /**
     * Given a btree page and a cell index (0 means the first cell on the page,
     * 1 means the second cell, and so forth) return a pointer to the cell
     * content.
     *
     * This routine works only for pages that do not contain overflow cells.
     */
    public ISqlJetMemoryPointer findCell(int i) {
        return aData.pointer(maskPage & aData.getShortUnsigned(cellOffset + 2 * i));
    }

    /**
     * If the cell pCell, part of page pPage contains a pointer to an overflow
     * page, insert an entry into the pointer-map for the overflow page.
     *
     * @throws SqlJetException
     */
    public void ptrmapPutOvflPtr(ISqlJetMemoryPointer pCell) throws SqlJetException {
        assert (pCell != null);
        SqlJetBtreeCellInfo info = parseCellPtr(pCell);
        assert ((info.nData + (intKey ? 0 : info.getnKey())) == info.nPayload);
        if ((info.nData + (intKey ? 0 : info.getnKey())) > info.nLocal) {
            int ovfl = pCell.getInt(info.iOverflow);
            pBt.ptrmapPut(ovfl, SqlJetPtrMapType.PTRMAP_OVERFLOW1, pgno);
        }
    }

    /**
     * Parse a cell content block and fill in the CellInfo structure. There are
     * two versions of this function. sqlite3BtreeParseCell() takes a cell index
     * as the second argument and sqlite3BtreeParseCellPtr() takes a pointer to
     * the body of the cell as its second argument.
     *
     * @param pCell
     *            Pointer to the cell text.
     * @return
     */
    SqlJetBtreeCellInfo parseCellPtr(ISqlJetMemoryPointer pCell) {
        assert (pBt.mutex.held());

        int nPayload; /* Number of bytes of cell payload */
        int n = getChildPtrSize();     /* Number bytes in cell content header */
        int nData;
        long nKey;
        
        if (intKey) {
            if (hasData) {
            	SqlJetVarintResult32 res = pCell.getVarint32(n);
            	nPayload = res.getValue();
                n += res.getOffset();
            } else {
                nPayload = 0;
            }
            SqlJetVarintResult varint = pCell.getVarint(n);
            n += varint.getOffset();
            nKey = varint.getValue();
            nData = nPayload;
        } else {
            nData = 0;
            SqlJetVarintResult32 res = pCell.getVarint32(n);
            n += res.getOffset();
            nPayload = res.getValue();
            nKey = nPayload;
        }
        
        int iOverflow;
        int nLocal;
        int nSize; /* Total size of cell content in bytes */
        if (nPayload <= this.maxLocal) {
            /*
             * This is the (easy) common case where the entire payload fits on
             * the local page. No overflow is required.
             */
            nSize = nPayload + n;
            nLocal = nPayload;
            iOverflow = 0;
            if ((nSize & ~3) == 0) {
                nSize = 4; /* Minimum cell size is 4 */
            }
        } else {
            /*
             * If the payload will not fit completely on the local page, we have
             * to decide how much to store locally and how much to spill onto
             * overflow pages. The strategy is to minimize the amount of unused
             * space on overflow pages while keeping the amount of local storage
             * in between minLocal and maxLocal.
             *
             * Warning: changing the way overflow payload is distributed in any
             * way will result in an incompatible file format.
             */

        	/* Overflow payload available for local storage */
            int surplus = minLocal + (nPayload - minLocal) % (pBt.usableSize - 4);
            if (surplus <= maxLocal) {
                nLocal = surplus;
            } else {
                nLocal = minLocal;
            }
            iOverflow = nLocal + n;
            nSize = iOverflow + 4;
        }
        SqlJetBtreeCellInfo pInfo = new SqlJetBtreeCellInfo(pCell, n, nData, nPayload, iOverflow, nLocal, nSize);
        pInfo.setnKey(nKey);

        return pInfo;

    }

    /**
     * @param iCell
     *            The cell index. First cell is 0
     * @return
     */
    public SqlJetBtreeCellInfo parseCell(int iCell) {
        return parseCellPtr(findCell(iCell));
    }

    /**
     * Set up a raw page so that it looks like a database page holding no
     * entries.
     *
     * @param sqlJetBtree
     * @param flags
     * @throws SqlJetException
     */
    void zeroPage(int flags) throws SqlJetException {
        int hdr = getHdrOffset();

        assert (pDbPage.getPageNumber() == pgno);
        assert (pDbPage.getExtra() == this);
        assert (pDbPage.getData().getBuffer() == aData.getBuffer());
        assert (pBt.mutex.held());

        aData.putByteUnsigned(hdr, (short) flags);
        int first = hdr + 8 + 4 * ((flags & SqlJetMemPage.PTF_LEAF) == 0 ? 1 : 0);
        // SqlJetUtility.memset(data, hdr + 1, (byte) 0, 4);
        SqlJetUtility.put4byte(aData, hdr + 1, 0);
        //
        aData.putByteUnsigned(hdr + 7, (short) 0);
        SqlJetUtility.put2byte(aData, hdr + 5, pBt.usableSize);
        nFree = pBt.usableSize - first;
        decodeFlags(flags);
        cellOffset = first;
        aOvfl.clear();
        assert (pBt.pageSize >= 512 && pBt.pageSize <= 32768);
        maskPage = pBt.pageSize - 1;
        nCell = 0;
        isInit = true;
    }

    /**
     * Add a page of the database file to the freelist. unref() is NOT called
     * for pPage.
     */
    public void freePage() throws SqlJetException {
        SqlJetMemPage pPage1 = pBt.pPage1;
        int n, k;

        /* Prepare the page for freeing */
        assert (pBt.mutex.held());
        assert (this.pgno > 1);
        this.isInit = false;

        /* Increment the free page count on pPage1 */
        pPage1.pDbPage.write();
        n = pPage1.aData.getInt(36);
        put4byte(pPage1.aData, 36, n + 1);

        if (ISqlJetConfig.SECURE_DELETE) {
            /*
             * If the SQLITE_SECURE_DELETE compile-time option is enabled, then
             * always fully overwrite deleted information with zeros.
             */
            pDbPage.write();
            memset(aData, (byte) 0, pBt.pageSize);
        }

        /*
         * If the database supports auto-vacuum, write an entry in the
         * pointer-map to indicate that the page is free.
         */
        if (isAutoVacuum()) {
            pBt.ptrmapPut(pgno, SqlJetPtrMapType.PTRMAP_FREEPAGE, 0);
        }

        if (n == 0) {
            /* This is the first free page */
            pDbPage.write();
            memset(aData, (byte) 0, 8);
            put4byte(pPage1.aData, 32, pgno);
            traceInt("FREE-PAGE: %d first\n", this.pgno);
        } else {
            /*
             * Other free pages already exist. Retrive the first trunk page* of
             * the freelist and find out how many leaves it has.
             */
            SqlJetMemPage pTrunk;
            pTrunk = pBt.getPage(pPage1.aData.getInt(32), false);
            k = pTrunk.aData.getInt(4);
            if (k >= pBt.usableSize / 4 - 8) {
                /*
                 * The trunk is full. Turn the page being freed into a new*
                 * trunk page with no leaves.** Note that the trunk page is not
                 * really full until it contains* usableSize/4 - 2 entries, not
                 * usableSize/4 - 8 entries as we have* coded. But due to a
                 * coding error in versions of SQLite prior to* 3.6.0, databases
                 * with freelist trunk pages holding more than* usableSize/4 - 8
                 * entries will be reported as corrupt. In order* to maintain
                 * backwards compatibility with older versions of SQLite,* we
                 * will contain to restrict the number of entries to
                 * usableSize/4 - 8* for now. At some point in the future (once
                 * everyone has upgraded* to 3.6.0 or later) we should consider
                 * fixing the conditional above* to read "usableSize/4-2"
                 * instead of "usableSize/4-8".
                 */
                pDbPage.write();
                put4byte(aData, pTrunk.pgno);
                put4byte(aData, 4, 0);
                put4byte(pPage1.aData, 32, pgno);
                traceInt("FREE-PAGE: %d new trunk page replacing %d\n", this.pgno, pTrunk.pgno);
            } else if (k < 0) {
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            } else {
                /* Add the newly freed page as a leaf on the current trunk */
                pTrunk.pDbPage.write();
                put4byte(pTrunk.aData, 4, k + 1);
                put4byte(pTrunk.aData, 8 + k * 4, pgno);
                if (ISqlJetConfig.SECURE_DELETE) {
                    pDbPage.dontWrite();
                }
                traceInt("FREE-PAGE: %d leaf on trunk page %d\n", this.pgno, pTrunk.pgno);
            }
            releasePage(pTrunk);
        }
    }

    /**
     ** Free any overflow pages associated with the given Cell.
     */
    public void clearCell(ISqlJetMemoryPointer pCell) throws SqlJetException {
        assert (pBt.mutex.held());
        SqlJetBtreeCellInfo info = parseCellPtr(pCell);
        if (info.iOverflow == 0) {
            return; /* No overflow pages. Return without doing anything */
        }
        int ovflPgno = pCell.getInt(info.iOverflow);
        int ovflPageSize = pBt.usableSize - 4;
        int nOvfl = (info.nPayload - info.nLocal + ovflPageSize - 1) / ovflPageSize;
        assert (ovflPgno == 0 || nOvfl > 0);
        
        while (nOvfl-- != 0) {
            SqlJetMemPage[] pOvfl = new SqlJetMemPage[1];
            if (ovflPgno <2 || ovflPgno > pBt.pPager.getPageCount()) {
                /* 0 is not a legal page number and page 1 cannot be an
                 ** overflow page. Therefore if ovflPgno<2 or past the end of the
                 ** file the database must be corrupt. */
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            }

            ovflPgno = pBt.getOverflowPage(ovflPgno, pOvfl, (nOvfl == 0) ? 0 : ovflPgno);
            pOvfl[0].freePage();
            pOvfl[0].pDbPage.unref();
        }
    }

    /**
     ** Compute the total number of bytes that a Cell needs in the cell data area
     * of the btree-page. The return number includes the cell data header and
     * the local payload, but not any overflow page or the space used by the
     * cell pointer.
     */
    int cellSize(int iCell) {
        //SqlJetBtreeCellInfo info = parseCell(iCell);
        //return info.nSize;
    	return cellSizePtr(findCell(iCell));
    }

	int cellSizePtr(ISqlJetMemoryPointer pCell) {
		// SqlJetBtreeCellInfo info = parseCellPtr(pCell);
		// return info.nSize;

		final SqlJetMemPage pPage = this;

		final ISqlJetMemoryPointer pIter = pCell.getMoved(pPage.getChildPtrSize());
		int nSize = 0;

		if (pPage.intKey) {
			if (pPage.hasData) {
				SqlJetVarintResult32 res = pIter.getVarint32();
				nSize = res.getValue();
				pIter.movePointer(res.getOffset());
			}

			/*
			 * pIter now points at the 64-bit integer key value, a variable
			 * length* integer. The following block moves pIter to point at the
			 * first byte* past the end of the key value.
			 */
			int pEnd = pIter.getPointer() + 9;
			// while( (*pIter++)&0x80 && pIter<pEnd );
			while (pIter.getPointer() < pEnd) {
				int b = pIter.getByteUnsigned();
				pIter.movePointer(1);
				if ((b & 0x80) == 0) {
					break;
				}
			}
		} else {
			SqlJetVarintResult32 res = pIter.getVarint32();
			nSize = res.getValue();
			pIter.movePointer(res.getOffset());
		}

		if (nSize > pPage.maxLocal) {
			int minLocal = pPage.minLocal;
			nSize = minLocal + (nSize - minLocal)
					% (pPage.pBt.usableSize - 4);
			if (nSize > pPage.maxLocal) {
				nSize = minLocal;
			}
			nSize += 4;
		}
		nSize += (pIter.getPointer() - pCell.getPointer());

		/* The minimum size of any cell is 4 bytes. */
		return Integer.max(4, nSize);
	}


    /**
     * Remove the i-th cell from pPage. This routine effects pPage only. The
     * cell content is not freed or deallocated. It is assumed that the cell
     * content has been copied someplace else. This routine just removes the
     * reference to the cell from pPage.
     *
     * "sz" must be the number of bytes in the cell.
     *
     * @param idx
     * @param sz
     * @throws SqlJetException
     */
    public void dropCell(int idx, int sz) throws SqlJetException {

        final SqlJetMemPage pPage = this;

        int pc; /* Offset to cell content of cell being deleted */
        ISqlJetMemoryPointer data; /* pPage->aData */
        ISqlJetMemoryPointer ptr; /* Used to move bytes around within data[] */

        assert (idx >= 0 && idx < pPage.nCell);
        assert (sz == pPage.cellSize(idx));
        assert (pPage.pBt.mutex.held());
        data = pPage.aData;
        ptr = data.pointer(pPage.cellOffset + 2 * idx);
        pc = ptr.getShortUnsigned();
        if ((pc < pPage.getHdrOffset() + 6 + (pPage.leaf ? 0 : 4)) || (pc + sz > pPage.pBt.usableSize)) {
            throw new SqlJetException(SqlJetErrorCode.CORRUPT);
        }
        pPage.freeSpace(pc, sz);
        final ISqlJetMemoryPointer endPtr = data.pointer(pPage.cellOffset + 2*pPage.nCell - 2);
        while( ptr.getPointer() < endPtr.getPointer() ){
            put2byte(ptr, ptr.getShortUnsigned(2));
            ptr.movePointer(2);
        }
        //put2byte(endPtr, 0);
        pPage.nCell--;
        put2byte(data, pPage.getHdrOffset() + 3, pPage.nCell);
        pPage.nFree += 2;
    }

    /*
     * * Return a section of the pPage->aData to the freelist.* The first byte
     * of the new free block is pPage->aDisk[start]* and the size of the block
     * is "size" bytes.** Most of the effort here is involved in coalesing
     * adjacent* free blocks into a single big free block.
     */
    private void freeSpace(int start, int size) throws SqlJetException {

        SqlJetMemPage pPage = this;

        int addr, pbegin, hdr;
        ISqlJetMemoryPointer data = pPage.aData;

        hdr = pPage.getHdrOffset();
        
        assert (pPage.pBt != null);
        assert (start >= hdr + 6 + (pPage.leaf ? 0 : 4));
        assert ((start + size) <= pPage.pBt.usableSize);
        assert (pPage.pBt.mutex.held());
        assert (size >= 0); /* Minimum cell size is 4 */

        if (ISqlJetConfig.SECURE_DELETE) {
            /*
             * Overwrite deleted information with zeros when the SECURE_DELETE*
             * option is enabled at compile-time
             */
            memset(data, start, (byte) 0, size);
        }

        /* Add the space back into the linked list of freeblocks */
        addr = hdr + 1;
        while ((pbegin = data.getShortUnsigned(addr)) < start && pbegin > 0) {
            assert (pbegin <= pPage.pBt.usableSize - 4);
            if (pbegin <= addr) {
                throw new SqlJetException(SqlJetErrorCode.CORRUPT);
            }
            addr = pbegin;
        }
        if (pbegin > pPage.pBt.usableSize - 4) {
            throw new SqlJetException(SqlJetErrorCode.CORRUPT);
        }
        assert (pbegin > addr || pbegin == 0);
        put2byte(data, addr, start);
        put2byte(data, start, pbegin);
        put2byte(data, start + 2, size);
        pPage.nFree += size;

        /* Coalesce adjacent free blocks */
        addr = hdr + 1;
        while ((pbegin = data.getShortUnsigned(addr)) > 0) {
            int pnext, psize, x;
            assert (pbegin > addr);
            assert (pbegin <= pPage.pBt.usableSize - 4);
            pnext = data.getShortUnsigned(pbegin);
            psize = data.getShortUnsigned(pbegin + 2);
            if (pbegin + psize + 3 >= pnext && pnext > 0) {
                int frag = pnext - (pbegin + psize);
                if ((frag < 0) || (frag > data.getByteUnsigned(hdr + 7))) {
                    throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                }
                data.putByteUnsigned(hdr + 7, (byte) (data.getByteUnsigned(hdr + 7) - (byte) frag));
                x = data.getShortUnsigned(pnext);
                put2byte(data, pbegin, x);
                x = pnext + data.getShortUnsigned(pnext + 2) - pbegin;
                put2byte(data, pbegin + 2, x);
            } else {
                addr = pbegin;
            }
        }

        /* If the cell content area begins with a freeblock, remove it. */
        if (data.getByteUnsigned(hdr + 1) == data.getByteUnsigned(hdr + 5)
                && data.getByteUnsigned(hdr + 2) == data.getByteUnsigned(hdr + 6)) {
            int top;
            pbegin = data.getShortUnsigned(hdr + 1);
            data.copyFrom(hdr + 1, data, pbegin, 2);
            top = data.getShortUnsigned(hdr + 5) + data.getShortUnsigned(pbegin + 2);
            put2byte(data, hdr + 5, top);
        }
        assert (pPage.pDbPage.isWriteable());
    }

    /**
     * Insert a new cell on pPage at cell index "i". pCell points to the content
     * of the cell.
     *
     * If the cell content will fit on the page, then put it there. If it will
     * not fit, then make a copy of the cell content into pTemp if pTemp is not
     * null. Regardless of pTemp, allocate a new entry in pPage->aOvfl[] and
     * make it point to the cell content (either in pTemp or the original pCell)
     * and also record its index. Allocating a new entry in pPage->aCell[]
     * implies that pPage->nOverflow is incremented.
     *
     * If nSkip is non-zero, then do not copy the first nSkip bytes of the cell.
     * The caller will overwrite them after this function returns. If nSkip is
     * non-zero, then pCell may not point to an invalid memory location (but
     * pCell+nSkip is always valid).
     *
     * @param i
     *            New cell becomes the i-th cell of the page
     * @param pCell
     *            Content of the new cell
     * @param sz
     *            Bytes of content in pCell
     * @param pTemp
     *            Temp storage space for pCell, if needed
     * @param nSkip
     *            Do not write the first nSkip bytes of the cell
     *
     * @throws SqlJetException
     */
    public void insertCell(int i, ISqlJetMemoryPointer pCell, int sz, ISqlJetMemoryPointer pTemp, int iChild)
            throws SqlJetException {
    	int nSkip = (iChild>0 ? 4 : 0);

        assert (i >= 0 && i <= this.nCell + this.aOvfl.size());
        assert (this.nCell <= this.pBt.MX_CELL() && this.pBt.MX_CELL() <= 5460);
        assert (sz == this.cellSizePtr(pCell) || (sz==8 && iChild>0) );
        assert (this.pBt.mutex.held());
        if (!this.aOvfl.isEmpty() || sz + 2 > this.nFree) {
            if (pTemp != null) {
            	pTemp.copyFrom(nSkip, pCell, nSkip, sz - nSkip);
                pCell = pTemp;
            }
            if( iChild>0 ) {
                put4byte(pCell, iChild);
            }
            this.aOvfl.add(new SqlJetOvflCell(pCell, i));
        } else {
            this.pDbPage.write();
            assert (this.pDbPage.isWriteable());
            int hdr = this.getHdrOffset();                /* Offset into data[] of the page header */
            int top = aData.getShortUnsigned(hdr + 5);           /* First byte of content for any cell in data[] */
            int end = cellOffset + 2 * this.nCell + 2;    /* First byte past the last cell pointer in data[] */
            int ins = cellOffset + 2 * i;                 /* Index in data[] where new cell pointer is inserted */
            if (end > top - sz) {
                this.defragmentPage();
                top = aData.getShortUnsigned(hdr + 5);
                assert (end + sz <= top);
            }
            int idx = this.allocateSpace(sz);             /* Where to write new cell content in data[] */
            assert (idx > 0);
            assert (end <= aData.getShortUnsigned(hdr + 5));
            SqlJetAssert.assertTrue(idx + sz <= this.pBt.usableSize, SqlJetErrorCode.CORRUPT);
            this.nCell++;
            this.nFree -= (2 + sz);
            aData.copyFrom(idx + nSkip, pCell, nSkip, sz - nSkip);
            if( iChild>0 ) {
                put4byte(aData, idx, iChild);
            }
            for (int j = end - 2; j > ins; j -= 2) {
                aData.putByteUnsigned(j, aData.getByteUnsigned(j - 2));
                aData.putByteUnsigned(j + 1, aData.getByteUnsigned(j - 1));
            }
            put2byte(aData, ins, idx);
            put2byte(aData, hdr + 3, this.nCell);
            if (this.pBt.autoVacuum) {
                /*
                 * The cell may contain a pointer to an overflow page. If so,
                 * write the entry for the overflow page into the pointer map.
                 */
                SqlJetBtreeCellInfo info = this.parseCellPtr(pCell);
                assert ((info.nData + (this.intKey ? 0 : info.getnKey())) == info.nPayload);
                if ((info.nData + (this.intKey ? 0 : info.getnKey())) > info.nLocal) {
                    int pgnoOvfl = pCell.getInt(info.iOverflow);
                    this.pBt.ptrmapPut(pgnoOvfl, SqlJetPtrMapType.PTRMAP_OVERFLOW1, this.pgno);
                }
            }
        }
    }

    /**
     * Allocate nByte bytes of space on a page.
     *
     * Return the index into pPage->aData[] of the first byte of the new
     * allocation. The caller guarantees that there is enough space. This
     * routine will never fail.
     *
     * If the page contains nBytes of free space but does not contain nBytes of
     * contiguous free space, then this routine automatically calls
     * defragementPage() to consolidate all free space before allocating the new
     * chunk.
     *
     * @param nByte
     * @return
     *
     * @throws SqlJetException
     */
    private int allocateSpace(int nByte) throws SqlJetException {

        final SqlJetMemPage pPage = this;

        int addr, pc, hdr;
        ISqlJetMemoryPointer data;

        data = pPage.aData;
        assert (pPage.pDbPage.isWriteable());
        assert (pPage.pBt != null);
        assert (pPage.pBt.mutex.held());
        
        assert (nByte >= 0); /* Minimum cell size is 4 */
        assert (pPage.nFree >= nByte);
        assert (pPage.aOvfl.isEmpty());
        int usableSize = pPage.pBt.usableSize;
        assert (nByte < usableSize - 8);
        
        hdr = pPage.getHdrOffset();

        int nFrag = data.getByteUnsigned(hdr + 7);
        assert (pPage.cellOffset == hdr + 12 - 4*(pPage.leaf ? 1 : 0));
        int gap = pPage.cellOffset + 2*pPage.nCell;
        int top = data.getMoved(hdr + 5).getShortUnsigned();
        if (gap > top) {
            throw new SqlJetException(SqlJetErrorCode.CORRUPT);
        }
        if (nFrag >= 60) {
            pPage.defragmentPage();
            top = data.getMoved(hdr + 5).getShortUnsigned();
        } else if (gap + 2 <= top) {
            addr = hdr + 1; 
            while((pc = data.getShortUnsigned(addr)) > 0) {
                if (pc > usableSize - 4 || pc < addr + 4) {
                    throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                }
                int size = data.getShortUnsigned(pc + 2);
                if (size >= nByte) {
                    int x = size - nByte;
                    if (x < 4) {
                    	data.copyFrom(addr, data, pc, 2);
                        data.putByteUnsigned(hdr + 7, nFrag + x);
                    } else if (size + pc > usableSize) {
                        throw new SqlJetException(SqlJetErrorCode.CORRUPT);
                    } else {
                        put2byte(data, pc +2 , x);
                    }
                    return pc + x;
                }
                addr = pc;
            }
        }
        if (gap + 2 + nByte > top) {
            pPage.defragmentPage();
            top = data.getShortUnsigned(hdr + 5);
            assert (gap + nByte <= top);
        }
        top -= nByte;
        put2byte(data, hdr + 5, top);
        assert (top + nByte <= pPage.pBt.usableSize);
        return top;
    }

    /**
     * Defragment the page given. All Cells are moved to the end of the page and
     * all free space is collected into one big FreeBlk that occurs in between
     * the header and cell pointer array and the cell content area.
     *
     * @throws SqlJetException
     */
    private void defragmentPage() throws SqlJetException {

        final SqlJetMemPage pPage = this;

        int i; /* Loop counter */
        int pc; /* Address of a i-th cell */
        int hdr; /* Offset to the page header */
        int size; /* Size of a cell */
        int usableSize; /* Number of usable bytes on a page */
        int cellOffset; /* Offset to the cell pointer array */
        int cbrk; /* Offset to the cell content area */
        int nCell; /* Number of cells on the page */
        ISqlJetMemoryPointer data; /* The page data */
        ISqlJetMemoryPointer temp; /* Temp area for cell content */
        int iCellFirst;            /* First allowable cell index */
        int iCellLast;             /* Last possible cell index */

        assert (pPage.pDbPage.isWriteable());
        assert (pPage.pBt != null);
        assert (pPage.pBt.usableSize <= ISqlJetLimits.SQLJET_MAX_PAGE_SIZE);
        assert (pPage.aOvfl.isEmpty());
        assert (pPage.pBt.mutex.held());
        temp = pPage.pBt.pPager.getTempSpace();
        data = pPage.aData;
        hdr = pPage.getHdrOffset();
        cellOffset = pPage.cellOffset;
        nCell = pPage.nCell;
        assert (nCell == data.getShortUnsigned(hdr + 3));
        usableSize = pPage.pBt.usableSize;
        cbrk = data.getShortUnsigned(hdr + 5);
        temp.copyFrom(cbrk, data, cbrk, usableSize - cbrk);
        cbrk = usableSize;
        iCellFirst = cellOffset + 2*nCell;
        iCellLast = usableSize - 4;
        for(i=0; i<nCell; i++){
          final ISqlJetMemoryPointer pAddr = data.getBuffer().getPointer(cellOffset + i*2); /* The i-th cell pointer */
          pc = pAddr.getShortUnsigned();
          if( pc<iCellFirst || pc>iCellLast ){
              throw new SqlJetException(SqlJetErrorCode.CORRUPT);
          }
          assert( pc>=iCellFirst && pc<=iCellLast );
          size = pPage.cellSizePtr(temp.getBuffer().getPointer(pc));
          cbrk -= size;
          if( cbrk<iCellFirst || pc+size>usableSize ){
              throw new SqlJetException(SqlJetErrorCode.CORRUPT);
          }
          assert( cbrk+size<=usableSize && cbrk>=iCellFirst );
          data.copyFrom(cbrk, temp, pc, size);
          put2byte(pAddr, cbrk);
        }
        assert( cbrk>=iCellFirst );
        put2byte(data, hdr + 5, cbrk);
        data.putByteUnsigned(hdr + 1, (byte) 0);
        data.putByteUnsigned(hdr + 2, (byte) 0);
        data.putByteUnsigned(hdr + 7, (byte) 0);
        memset(data, iCellFirst, (byte) 0, cbrk - iCellFirst);
        assert (pPage.pDbPage.isWriteable());
        if (cbrk - iCellFirst != pPage.nFree) {
            throw new SqlJetException(SqlJetErrorCode.CORRUPT);
        }
    }

    /**
     * This a more complex version of findCell() that works for pages that do
     * contain overflow cells. See insert
     *
     * @param iCell
     * @return
     */
    public ISqlJetMemoryPointer findOverflowCell(int iCell) {
        final SqlJetMemPage pPage = this;
        assert (pPage.pBt.mutex.held());
        for (SqlJetOvflCell pOvfl : pPage.aOvfl) {
            int k = pOvfl.getIdx();
            if (k <= iCell) {
                if (k == iCell) {
                    return pOvfl.getpCell();
                }
                iCell--;
            }
        }
        return pPage.findCell(iCell);
    }

    /**
     * Add a list of cells to a page. The page should be initially empty. The
     * cells are guaranteed to fit on the page.
     *
     * @param nCell
     *            The number of cells to add to this page
     * @param apCell
     *            Pointers to cell bodies
     * @param aSize
     *            Sizes of the cells
     *
     * @throws SqlJetException
     */
    public void assemblePage(int nCell, ISqlJetMemoryPointer[] apCell, int apCellPos, int[] aSize, int aSizePos) throws SqlJetException {
        final SqlJetMemPage pPage = this;

        int i; /* Loop counter */
        int hdr = pPage.getHdrOffset(); /* Index of page header */
        
        ISqlJetMemoryPointer data = pPage.aData; /* Data for the page */
        int nUsable = pPage.pBt.usableSize;

        assert (pPage.aOvfl.isEmpty());
        assert (pPage.pBt.mutex.held());
        assert (nCell >= 0 && nCell <= pPage.pBt.MX_CELL() && pPage.pBt.MX_CELL() <= 10921);
        assert (pPage.pDbPage.isWriteable());
        assert (pPage.nCell == 0);
        assert (data.getMoved(hdr + 5).getShortUnsigned() == nUsable);
        
        ISqlJetMemoryPointer pCellPtr = data.getMoved(pPage.cellOffset + nCell*2);
        int cellbody = nUsable;
        for(i = nCell - 1; i >= 0; i--) {
            int sz = aSize[apCellPos + i];
            pCellPtr = pCellPtr.getMoved(-2);
            cellbody -= sz;
            put2byte(pCellPtr, cellbody);
            data.getMoved(cellbody).copyFrom(apCell[apCellPos + i], sz);
        }
        put2byte(data.getMoved(hdr + 3), nCell);
        put2byte(data.getMoved(hdr + 5), cellbody);
        pPage.nFree -= (2 * nCell + pPage.pBt.usableSize - cellbody);
        pPage.nCell = nCell;
    }

    /**
     * Page pParent is an internal (non-leaf) tree page. This function asserts
     * that page number iChild is the left-child if the iIdx'th cell in page
     * pParent. Or, if iIdx is equal to the total number of cells in pParent,
     * that page number iChild is the right-child of the page.
     *
     * @param iIdx
     * @param iChild
     */
    public void assertParentIndex(int iIdx, int iChild) {
        final SqlJetMemPage pParent = this;
        assert (iIdx <= pParent.nCell);
        if (iIdx == pParent.nCell) {
            assert (pParent.aData.getInt(pParent.getHdrOffset() + 8) == iChild);
        } else {
            assert (pParent.findCell(iIdx).getInt() == iChild);
        }
    }

    /**
     * Create the byte sequence used to represent a cell on page pPage and write
     * that byte sequence into pCell[]. Overflow pages are allocated and filled
     * in as necessary. The calling procedure is responsible for making sure
     * sufficient space has been allocated for pCell[].
     *
     * Note that pCell does not necessary need to point to the pPage->aData
     * area. pCell might point to some temporary storage. The cell will be
     * constructed in this temporary area then copied into pPage->aData later.
     *
     * @param pCell
     *            Complete text of the cell
     * @param pKey
     *            The key
     * @param nKey
     *            The key
     * @param pData
     *            The data
     * @param nData
     *            The data
     * @param nZero
     *            Extra zero bytes to append to pData
     *
     * @return cell size
     *
     * @throws SqlJetException
     */
    public int fillInCell(ISqlJetMemoryPointer pCell, ISqlJetMemoryPointer pKey, long nKey, ISqlJetMemoryPointer pData,
            int nData, int nZero) throws SqlJetException {
        ISqlJetMemoryPointer pSrc;
        int nSrc, n;
        SqlJetMemPage pOvfl = null;
        SqlJetMemPage pToRelease = null;
        ISqlJetMemoryPointer pPayload;
        SqlJetBtreeShared pBt = this.pBt;
        int[] pgnoOvfl = { 0 };
        int nHeader;
        SqlJetBtreeCellInfo info;

        assert (this.pBt.mutex.held());

        /*
         * pPage is not necessarily writeable since pCell might be auxiliary*
         * buffer space that is separate from the pPage buffer area
         */
        assert (pCell.getBuffer() != this.aData.getBuffer() || this.pDbPage.isWriteable());

        /* Fill in the header. */
        nHeader = 0;
        if (!this.leaf) {
            nHeader += 4;
        }
        if (this.hasData) {
            nHeader += pCell.pointer(nHeader).putVarint(nData + nZero);
        } else {
            nData = nZero = 0;
        }
        nHeader += pCell.pointer(nHeader).putVarint(nKey);
        info = this.parseCellPtr(pCell);
        assert (info.nHeader == nHeader);
        assert (info.getnKey() == nKey);
        assert (info.nData == nData + nZero);

        /* Fill in the payload */
        int nPayload = nData + nZero;
        if (this.intKey) {
            pSrc = pData;
            nSrc = nData;
            nData = 0;
        } else {
            /* TBD: Perhaps raise SQLITE_CORRUPT if nKey is larger than 31 bits? */
            nPayload += (int) nKey;
            pSrc = pKey;
            nSrc = (int) nKey;
        }
        int pnSize = info.nSize;
        int spaceLeft = info.nLocal;
        pPayload = pCell.pointer(nHeader);
        ISqlJetMemoryPointer pPrior = pCell.pointer(info.iOverflow);

        while (nPayload > 0) {
            if (spaceLeft == 0) {
                int pgnoPtrmap = pgnoOvfl[0]; /*
                                               * Overflow page pointer-map entry
                                               * page
                                               */
                if (pBt.autoVacuum) {
                    do {
                        pgnoOvfl[0]++;
                    } while (pBt.PTRMAP_ISPAGE(pgnoOvfl[0]) || pgnoOvfl[0] == pBt.PENDING_BYTE_PAGE());
                }
                try {
                    pOvfl = pBt.allocatePage(pgnoOvfl, pgnoOvfl[0], false);
                    /*
                     * If the database supports auto-vacuum, and the second or
                     * subsequent* overflow page is being allocated, add an
                     * entry to the pointer-map* for that page now.** If this is
                     * the first overflow page, then write a partial entry* to
                     * the pointer-map. If we write nothing to this pointer-map
                     * slot,* then the optimistic overflow chain processing in
                     * clearCell()* may misinterpret the uninitialised values
                     * and delete the* wrong pages from the database.
                     */
                    if (pBt.autoVacuum) {
                    	SqlJetPtrMapType eType = (pgnoPtrmap != 0 ? SqlJetPtrMapType.PTRMAP_OVERFLOW2
                                : SqlJetPtrMapType.PTRMAP_OVERFLOW1);
                        try {
                            pBt.ptrmapPut(pgnoOvfl[0], eType, pgnoPtrmap);
                        } catch (SqlJetException e) {
                            releasePage(pOvfl);
                        }
                    }
                } catch (SqlJetException e) {
                    releasePage(pToRelease);
                    throw e;
                }

                /*
                 * If pToRelease is not zero than pPrior points into the data
                 * area* of pToRelease. Make sure pToRelease is still writeable.
                 */
                assert (pToRelease == null || pToRelease.pDbPage.isWriteable());

                /*
                 * If pPrior is part of the data area of pPage, then make sure
                 * pPage* is still writeable
                 */
                assert (pPrior.getBuffer() != this.aData.getBuffer() || this.pDbPage.isWriteable());

                put4byte(pPrior, pgnoOvfl[0]);
                releasePage(pToRelease);
                pToRelease = pOvfl;
                pPrior = pOvfl.aData;
                put4byte(pPrior, 0);
                pPayload = pOvfl.aData.pointer(4);
                spaceLeft = pBt.usableSize - 4;
            }
            n = nPayload;
            if (n > spaceLeft) {
				n = spaceLeft;
			}

            /*
             * If pToRelease is not zero than pPayload points into the data area
             * * of pToRelease. Make sure pToRelease is still writeable.
             */
            assert (pToRelease == null || pToRelease.pDbPage.isWriteable());

            /*
             * If pPayload is part of the data area of pPage, then make sure
             * pPage* is still writeable
             */
            assert (pPayload.getBuffer() != this.aData.getBuffer() || this.pDbPage.isWriteable());

            if (nSrc > 0) {
                if (n > nSrc) {
					n = nSrc;
				}
                assert (pSrc != null);
                pPayload.copyFrom(pSrc, n);
            } else {
                memset(pPayload, (byte) 0, n);
            }
            nPayload -= n;
            pPayload.movePointer(n);
            pSrc = pSrc.pointer(n);
            nSrc -= n;
            spaceLeft -= n;
            if (nSrc == 0) {
                nSrc = nData;
                pSrc = pData;
            }
        }
        releasePage(pToRelease);

        return pnSize;
    }

    /**
     * If the cell with index iCell on page pPage contains a pointer to an
     * overflow page, insert an entry into the pointer-map for the overflow
     * page.
     *
     * @param iCell
     *
     * @throws SqlJetException
     */
    public void ptrmapPutOvfl(int iCell) throws SqlJetException {
        SqlJetMemPage pPage = this;
        ISqlJetMemoryPointer pCell;
        assert (pPage.pBt.mutex.held());
        pCell = pPage.findOverflowCell(iCell);
        pPage.ptrmapPutOvflPtr(pCell);
    }

	public void copyNodeContent(final SqlJetMemPage pTo) throws SqlJetException {
		final SqlJetMemPage pFrom = this;
	    final SqlJetBtreeShared pBt = pFrom.pBt;
	    final ISqlJetMemoryPointer aFrom = pFrom.aData;
	    final ISqlJetMemoryPointer aTo = pTo.aData;
	    int iFromHdr = pFrom.getHdrOffset();
	    int iToHdr = ((pTo.pgno==1) ? 100 : 0);

	    assert( pFrom.isInit );
	    assert( pFrom.nFree>=iToHdr );
	    assert( aFrom.getMoved(iFromHdr+5).getShortUnsigned() <= pBt.usableSize );

	    /* Copy the b-tree node content from page pFrom to page pTo. */
	    int iData = aFrom.getMoved(iFromHdr+5).getShortUnsigned();
	    aTo.getMoved(iData).copyFrom(aFrom.getMoved(iData), pBt.usableSize-iData);
	    aTo.getMoved(iToHdr).copyFrom(aFrom.getMoved(iFromHdr), pFrom.cellOffset + 2*pFrom.nCell);

	    /* Reinitialize page pTo so that the contents of the MemPage structure
	    ** match the new data. The initialization of pTo can actually fail under
	    ** fairly obscure circumstances, even though it is a copy of initialized
	    ** page pFrom.
	    */
	    pTo.isInit = false;
	    pTo.initPage();

	    /* If this is an auto-vacuum database, update the pointer-map entries
	    ** for any b-tree or overflow pages that pTo now contains the pointers to.
	    */
	    if( isAutoVacuum() ){
	    	pTo.setChildPtrmaps();
	    }
	}

	@Override
	public SqlJetMemPage clone() throws CloneNotSupportedException {
		// TODO Auto-generated method stub
		final SqlJetMemPage clone = (SqlJetMemPage) super.clone();
		clone.aData = SqlJetUtility.memoryManager.allocatePtr(clone.pBt.pageSize);
		clone.aOvfl = aOvfl.clone();
		return clone;
	}

	/** 0 if leaf==1. 4 if leaf==0 */
	public byte getChildPtrSize() {
        return leaf ? (byte)0 : (byte)4;
	}
}
