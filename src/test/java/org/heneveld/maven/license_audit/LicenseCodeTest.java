package org.heneveld.maven.license_audit;

import org.heneveld.maven.license_audit.LicenseCodes;

import junit.framework.TestCase;

public class LicenseCodeTest extends TestCase {

    public void testCodes() {
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License, Version 1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("\nEclipse Public License, Version 1.0  "));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License (EPL), v 1 (EPL1)"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("Eclipse Public License - v 1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL v.1.0"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL1"));
        
        assertEquals("EPL", LicenseCodes.getLicenseCode("Eclipse Public License (EPL)"));
        assertEquals("EPL1", LicenseCodes.getLicenseCode("EPL1"));
        
        assertEquals("ASL2", LicenseCodes.getLicenseCode("Apache 2"));
        
        assertEquals("GPL", LicenseCodes.getLicenseCode("GNU license (gpl)"));
        assertEquals("LGPL3", LicenseCodes.getLicenseCode("lesser GNU license (lgpl3) 3"));
        assertNull(LicenseCodes.getLicenseCode("lesser GNU license (gpl3) 3"));
    }

}
