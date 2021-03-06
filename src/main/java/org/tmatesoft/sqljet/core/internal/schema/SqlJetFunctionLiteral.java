/**
 * SqlJetFunctionLiteral.java
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
import org.tmatesoft.sqljet.core.schema.ISqlJetExpression;

/**
 * @author TMate Software Ltd.
 * @author Dmitry Stadnik (dtrace@seznam.cz)
 */
public class SqlJetFunctionLiteral extends SqlJetExpression implements ISqlJetExpression {
    /**
     * Functions literal function.
     * 
     * @author TMate Software Ltd.
     * @author Dmitry Stadnik (dtrace@seznam.cz)
     */
    public enum Function {
        CURRENT_TIME, CURRENT_DATE, CURRENT_TIMESTAMP;

        public static Function decode(String s) {
            if ("current_time".equalsIgnoreCase(s)) {
                return CURRENT_TIME;
            } else if ("current_date".equalsIgnoreCase(s)) {
                return CURRENT_DATE;
            } else if ("current_timestamp".equalsIgnoreCase(s)) {
                return CURRENT_TIMESTAMP;
            }
            return null;
        }

        @Override
		public String toString() {
            switch (this) {
            case CURRENT_TIME:
                return "CURRENT_TIME";
            case CURRENT_DATE:
                return "CURRENT_DATE";
            case CURRENT_TIMESTAMP:
                return "CURRENT_TIMESTAMP";
            }
            return "";
        }
    }

    private final Function value;

    public SqlJetFunctionLiteral(CommonTree ast) {
        assert "function_literal".equalsIgnoreCase(ast.getText());
        value = Function.decode(ast.getChild(0).getText());
    }

    @Override
	public Function getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
