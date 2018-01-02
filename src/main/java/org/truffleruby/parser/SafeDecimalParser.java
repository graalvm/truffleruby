/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser;

import java.math.BigDecimal;

class SafeDecimalParser {

    /** Constant 2 */
    protected static final BigDecimal TWO = new BigDecimal(2);

    /** Lower allowed value */
    protected static final BigDecimal LOWER = new BigDecimal("2.22507385850720113605e-308");

    /** Upper allowed value */
    protected static final BigDecimal UPPER = new BigDecimal("2.22507385850720125958e-308");

    /** The middle of the bad interval - used for rounding bad values */
    protected static final BigDecimal MIDDLE = LOWER.add(UPPER).divide(TWO);

    /** Upper allowed value as Double */
    private static final Double UPPER_DOUBLE = Double.valueOf(UPPER.doubleValue());

    /** Lower allowed value as Double */
    private static final Double LOWER_DOUBLE = Double.valueOf(LOWER.doubleValue());

    /** Digit sequence to trigger the slow path */
    private static final String SUSPICIOUS_DIGITS = "22250738585072";

    /**
     * Heuristic test if we should look closer at the value
     * 
     * @param s
     *            The non-null input String
     * @return <code>true</code> if the value is suspicious, <code>false</code> otherwise
     */
    final protected static boolean isSuspicious(String s) {
        return digits(s).contains(SUSPICIOUS_DIGITS);
    }

    /**
     * Safe parsing of a String into a Double
     * 
     * @param s
     *            The input String, can be null
     * @return The Double value
     */
    final protected static Double decimalValueOf(String s) {
        Double result = null;
        if (s != null) {
            if (isSuspicious(s)) {
                // take the slow path
                result = parseSafely(s);
            } else {
                result = Double.valueOf(s);
            }
        }
        return result;
    }

    /**
     * Safe way of getting the double value<br>
     * prevents BigDecimal from calling Double.parseDouble()
     * 
     * @param number
     * @return the double value
     */
    final protected static double decimalValue(Number number) {
        double result = 0;
        if (number != null) {
            if (number instanceof BigDecimal) {
                result = decimalValue((BigDecimal) number);
            } else {
                result = number.doubleValue();
            }
        }
        return result;
    }

    /**
     * Safe way of getting the double value<br>
     * Prevents BigDecimal from calling Double.parseDouble()
     * 
     * @param bigDecimal
     * @return the double value
     */
    final protected static double decimalValue(BigDecimal bigDecimal) {
        double result = 0.0;
        if (bigDecimal != null) {
            if (isDangerous(bigDecimal)) {
                result = decimalValueOf(bigDecimal.toString()).doubleValue();
            } else {
                result = bigDecimal.doubleValue();
            }
        }
        return result;
    }

    /**
     * Slow parsing of a suspicious value
     * <p>
     * Rounding takes place if the value is inside the bad interval
     * 
     * @param s
     *            The non-null input String
     * @return the double value
     */
    final private static Double parseSafely(String s) {
        Double result;
        BigDecimal bd = new BigDecimal(s);
        if (isDangerous(bd)) {
            if (bd.compareTo(MIDDLE) >= 0) {
                result = UPPER_DOUBLE;
            } else {
                result = LOWER_DOUBLE;
            }
        } else {
            result = Double.valueOf(s);
        }
        return result;
    }

    /**
     * Extract the digits from a numeric string
     * 
     * @param s
     *            The non-null String value
     * @return A String containing only the digits
     */
    final private static String digits(String s) {
        char[] ca = s.toCharArray();
        int len = ca.length;
        StringBuilder b = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = ca[i];
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Tests if the value is in the dangerous interval
     * 
     * @param bd
     *            The big decimal value
     * @return <code>true</code> if the value is dangerous, <code>false</code> otherwise
     */
    final private static boolean isDangerous(BigDecimal bd) {
        return bd.compareTo(UPPER) < 0 && bd.compareTo(LOWER) > 0;
    }
}
