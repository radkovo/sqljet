/**
 * AutoincrementTest.java
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
package org.tmatesoft.sqljet.core.table;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tmatesoft.sqljet.core.AbstractNewDbTest;
import org.tmatesoft.sqljet.core.SqlJetException;

/**
 * @author TMate Software Ltd.
 * @author Sergey Scherbina (sergey.scherbina@gmail.com)
 * 
 */
public class AutoincrementTest extends AbstractNewDbTest {

    /**
     * @throws java.lang.Exception
     */
    @Override
	@Before
    public void setUp() throws Exception {
        super.setUp();
        db.write().asVoid(db -> db.createTable("CREATE TABLE t (i integer primary key autoincrement,a text)"));
    }

    /**
     * @throws java.lang.Exception
     */
    @Override
	@After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void checkSequenceTable() throws SqlJetException {
        Assert.assertNotNull(db.getTable("SQLITE_SEQUENCE"));
    }

    @Test
    public void checkInsertAutoinc() throws SqlJetException {
        final ISqlJetTable table = db.getTable("t");
        db.write().asVoid(db -> {
                for (int i = 0; i < 100; i++) {
                    table.insert(null, "aaa");
                }
        });
        db.read().asVoid(db -> {
                Assert.assertTrue(!table.lookup(null, Integer.valueOf(100)).eof());
        });
    }
}
