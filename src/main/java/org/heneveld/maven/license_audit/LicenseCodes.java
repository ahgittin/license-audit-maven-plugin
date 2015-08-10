package org.heneveld.maven.license_audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LicenseCodes {

    private final static Map<String,String> KNOWN_LICENSE_CODES_WITH_REGEXES = new LinkedHashMap<String, String>();
    static {
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("ASL2", "(asl|(the )?apache( public)?( software)?( license)?"
            + "([-, ]*\\(?asl2?\\)?)?)"
            + "[-, ]*(v(ersion|\\.)?)? *2(\\.0)?"
            + "([-, ]*\\(?asl2?\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("ASL", "(asl|(the )?apache( public)?( software)?( license)?"
            + "([-, ]*\\(?asl\\)?)?)");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("EPL1", "(epl|(the )?eclipse( public)?( software)?( license)?"
            + "([-, ]*\\(?epl1?\\)?)?)"
            + "[-, ]*(v(ersion|\\.)?)? *1(\\.0)?"
            + "([-, ]*\\(?epl1?\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("EPL", "(epl|(the )?eclipse( public)?( software)?( license)?"
            + "([-, ]*\\(?epl\\)?)?)");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("CPL1", "(the )?common public( license)?"
            + "([-, ]*\\(?cpl1?\\)?)?"
            + "[-, ]*(v(ersion|\\.)?)? *1(\\.0)?"
            + "([-, ]*\\(?cpl1?\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("MIT", "(the )?mit( license)?"
            + "([-, ]*\\(?mit\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("CDDL1", "(the )?common development( and)? distribution( license)?"
            + "([-, ]*\\(?cddl1?\\)?)?"
            + "[-, ]*(v(ersion|\\.)?)? *1(\\.0)?"
            + "([-, ]*\\(?cddl1?\\)?)?");
        
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("GPL3", "(gpl|(the )?gnu( general)?( public)?( license)?"
            + "([-, ]*\\(?gpl3?\\)?)?)"
            + "[-, ]*(v(ersion|\\.)?)? 3(\\.0)?"
            + "([-, ]*\\(?gpl3?\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("GPL", "(gpl|(the )?gnu( general)?( public)?( license)?"
            + "([-, ]*\\(?gpl\\)?)?)");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("LGPL3", "(lgpl|(the )?(gnu lesser|lesser gnu)( general)?( public)?( license)?"
            + "([-, ]*\\(?lgpl3?\\)?)?)"
            + "[-, ]*(v(ersion|\\.)?)? 3(\\.0)?"
            + "([-, ]*\\(?lgpl3?\\)?)?");
        KNOWN_LICENSE_CODES_WITH_REGEXES.put("LGPL", "(lgpl|(the )?(gnu lesser|lesser gnu)( general)?( public)?( license)?"
            + "([-, ]*\\(?lgpl\\)?)?)");

    }
    
    public static Map<String, String> getKnownLicenseCodesWithRegexes() {
        return Collections.unmodifiableMap(KNOWN_LICENSE_CODES_WITH_REGEXES);
    }
    
    /** Returns a license code for known license,
     * empty string if it looks like there is no license info, 
     * or null if it could not extract a code */ 
    public static String getLicenseCode(String licenseSummary) {
        if (licenseSummary==null || licenseSummary.length()==0) return "";
        
        String ls = licenseSummary.toLowerCase().trim();
        if (ls.matches("<no[ a-z]*>")) return "";

        for (Map.Entry<String,String> l: KNOWN_LICENSE_CODES_WITH_REGEXES.entrySet()) {
            if (ls.matches(l.getValue())) return l.getKey();
            if (ls.equals(l.getKey().toLowerCase())) return l.getKey();
        }
        return null;
    }

}
