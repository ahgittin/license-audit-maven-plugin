package org.heneveld.maven.license_audit.util;

import org.apache.maven.model.License;

import junit.framework.TestCase;

public class LicenseCodeTest extends TestCase {

    public void testGetCodesFromRegex() {
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License, Version 1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("\nEclipse Public License, Version 1.0  "));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License (EPL), v 1 (EPL1)"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License - v 1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License 1 (EPL-1.0.0)"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License 1 - EPL"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License 1 - EPL-1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL v.1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL1"));
        
        assertNull(LicenseCodes.getLicenseCode("Eclipse Public License 1 - EPL - 1 - fail"));
        assertNull(LicenseCodes.getLicenseCode("Eclipse Public License 0.9"));
        
        assertEquals("EPL", LicenseCodes.getLicenseCode("Eclipse Public License (EPL)"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL1"));
        
        assertEquals("ASL2", LicenseCodes.getLicenseCode("Apache 2"));
        
        assertEquals("GPL", LicenseCodes.getLicenseCode("GNU license (gpl)"));
        assertEquals("LGPL3", LicenseCodes.getLicenseCode("GNU lesser license (lgpl3) 3"));
        assertNull(LicenseCodes.getLicenseCode("lesser GNU license (gpl3) 3"));
        
        assertEquals("CDDL1.1", LicenseCodes.getLicenseCode("\nCDDL 1.1 "));
        
        assertEquals("BSD-2-Clause", LicenseCodes.getLicenseCode("simplified (freebsd) bsd 2 clause license"));
    }

    public void testGetCodesFromRegexLookupKnownNames() {
        for (String code: LicenseCodes.KNOWN_LICENSE_CODES_WITH_REGEX.keySet()) {
            License l = LicenseCodes.KNOWN_LICENSE_CODES_WITH_LICENSE.get(code);
            if (l!=null) {
                String name = l.getName();
                assertEquals("lookup of "+name, code, LicenseCodes.getLicenseCode(name));
            }
        }
    }
    
    public void testGetCodesFromRegexLookupKnownCodes() {
        for (String code: LicenseCodes.KNOWN_LICENSE_CODES_WITH_REGEX.keySet()) {
            License l = LicenseCodes.KNOWN_LICENSE_CODES_WITH_LICENSE.get(code);
            if (l!=null) {
                assertEquals("lookup of "+code, code, code);
            }
        }
    }

}
