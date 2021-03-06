/**
 * SqlJetScope.java
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

/**
 * Objects of these class describes bounded scope. 
 * Scope has two bounds, left and right, represented by the instances
 * of {@link SqlJetScopeBound} class.
 * 
 * Null left or right bound means that scope is not bounded from that side.
 * 
 * @author TMate Software Ltd.
 * @author Sergey Scherbina (sergey.scherbina@gmail.com)
 *
 */
public class SqlJetScope {
    
    public static class SqlJetScopeBound {
        private final Object[] myValue;
        private final boolean myIsInclusive;

        public SqlJetScopeBound(Object[] value) {
            this(value, true);
        }
        
        public SqlJetScopeBound(Object[] value, boolean inclusive) {
            myValue = value;
            myIsInclusive = inclusive;
        }
        
        public Object[] getValue() {
            return myValue;
        }
        public boolean isInclusive() {
            return myIsInclusive;
        }
        
        @Override
		public String toString() {
        	StringBuilder sb = new StringBuilder();
            sb.append(isInclusive() ? '[' : '(');
            if (getValue() == null) {
                sb.append(getValue());
            } else if (getValue().length > 0) {
                sb.append(getValue()[0]);
                if (getValue().length > 1) {
                    sb.append(",...");
                }
            }
            sb.append(isInclusive() ? ']' : ')');
            return sb.toString();
        }
    }
    
    private final SqlJetScopeBound myLeftBound;
    private final SqlJetScopeBound myRightBound;
    
    
    public SqlJetScope(Object[] leftKey, Object[] rightKey) {
        this(new SqlJetScopeBound(leftKey), new SqlJetScopeBound(rightKey));
    }

    public SqlJetScope(Object[] leftKey, boolean isLeftKeyIncluded, Object[] rightKey, boolean isRightKeyIncluded) {
        this(new SqlJetScopeBound(leftKey, isLeftKeyIncluded), new SqlJetScopeBound(rightKey, isRightKeyIncluded)); 
    }

    public SqlJetScope(SqlJetScopeBound leftBound, SqlJetScopeBound rightBound) {
        myLeftBound = leftBound == null ? new SqlJetScopeBound(null, true) : leftBound;
        myRightBound = rightBound == null ? new SqlJetScopeBound(null, true) : rightBound;
    }

    public SqlJetScopeBound getLeftBound() {
        return myLeftBound;
    }

    public SqlJetScopeBound getRightBound() {
        return myRightBound;
    }

    public SqlJetScope reverse() {
        return new SqlJetScope(myRightBound, myLeftBound);
    }
    
    @Override
	public String toString() {
        return myLeftBound + ":" + myRightBound; 
    }
}
