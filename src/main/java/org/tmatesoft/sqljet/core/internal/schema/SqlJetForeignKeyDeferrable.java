/**
 * SqlJetForeignKeyDeferrable.java
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
 */
package org.tmatesoft.sqljet.core.internal.schema;

import org.antlr.runtime.tree.CommonTree;
import org.tmatesoft.sqljet.core.schema.ISqlJetForeignKeyDeferrable;

/**
 * @author TMate Software Ltd.
 * @author Dmitry Stadnik (dtrace@seznam.cz)
 */
public class SqlJetForeignKeyDeferrable implements ISqlJetForeignKeyDeferrable {

    private final boolean not, deferred, immediate;

    public SqlJetForeignKeyDeferrable(CommonTree ast) {
        assert "deferrable".equalsIgnoreCase(ast.getText());
        boolean not = false, deferred = false, immediate = false;
        for (int i = 0; i < ast.getChildCount(); i++) {
            CommonTree child = (CommonTree) ast.getChild(i);
            if ("not".equalsIgnoreCase(child.getText())) {
                assert !not;
                not = true;
            } else if ("deferred".equalsIgnoreCase(child.getText())) {
                assert !deferred;
                assert !immediate;
                deferred = true;
            } else if ("immediate".equalsIgnoreCase(child.getText())) {
                assert !deferred;
                assert !immediate;
                immediate = true;
            } else {
                assert false;
            }
        }
        this.not = not;
        this.deferred = deferred;
        this.immediate = immediate;
    }

    @Override
	public boolean isNot() {
        return not;
    }

    @Override
	public boolean isInitiallyDeferred() {
        return deferred;
    }

    @Override
	public boolean isInitiallyImmediate() {
        return immediate;
    }

    @Override
    public String toString() {
    	StringBuilder buffer = new StringBuilder();
        if (isNot()) {
            buffer.append("NOT ");
        }
        buffer.append("DEFERRABLE");
        if (isInitiallyDeferred()) {
            buffer.append(" INITIALLY DEFERRED");
        }
        if (isInitiallyImmediate()) {
            buffer.append(" INITIALLY IMMEDIATE");
        }
        return buffer.toString();
    }
}
