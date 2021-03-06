/**
 * IsNullTest.java
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
package org.tmatesoft.sqljet.issues._130;


import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tmatesoft.sqljet.core.AbstractNewDbTest;
import org.tmatesoft.sqljet.core.schema.SqlJetConflictAction;
import org.tmatesoft.sqljet.core.table.ISqlJetCursor;
import org.tmatesoft.sqljet.core.table.ISqlJetTable;

/**
 * @author TMate Software Ltd.
 * @author Sergey Scherbina (sergey.scherbina@gmail.com)
 *
 */
public class IsNullTest extends AbstractNewDbTest {

    public static final String CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS beans (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT)";

    /**
     * @throws java.lang.Exception
     */
    @Override
	@Before
    public void setUp() throws Exception {
        super.setUp();
        db.createTable(CREATE_TABLE);
    }

    @Test
    public void testIsNull() throws Exception {
        db.write().asVoid(db -> {
              ISqlJetTable table = db.getTable("beans");
              table.insertOr(SqlJetConflictAction.REPLACE, null, "SOME MESSAGE");
          });

         db.read().asVoid(db -> {
              ISqlJetTable table = db.getTable("beans");
              ISqlJetCursor cur = table.lookup(table.getPrimaryKeyIndexName(), Integer.valueOf(1));
              if (!cur.eof()) {
                long v = cur.getInteger(0);
                Assert.assertEquals(1, v);
                boolean isNull = cur.isNull(0); // -> returns true
                Assert.assertFalse(isNull);
              }
          });
    }

}
