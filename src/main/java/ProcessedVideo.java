import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ProcessedVideo {
    private String gameName;
    String processedPath;
    private String uuid;

    ProcessedVideo(String vidPath, Pattern pattern) {
        Matcher vidPathMatcher = pattern.matcher(vidPath);
        vidPathMatcher.find();

        gameName = vidPathMatcher.group(3);
        processedPath = "/srv/grand_backup/samba/vids/processed/" + Common.strParse(gameName) + "/";
        ManageServer.executeCommand("mkdir -p " + processedPath); // + "; rm -f " + processedPath + "*");

        LocalDateTime timePoint = LocalDateTime.of(
                Integer.parseInt(vidPathMatcher.group(6)),
                Integer.parseInt(vidPathMatcher.group(4)),
                Integer.parseInt(vidPathMatcher.group(5)),
                Integer.parseInt(vidPathMatcher.group(7)),
                Integer.parseInt(vidPathMatcher.group(8)),
                Integer.parseInt(vidPathMatcher.group(9))
        );
        long createTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((gameName + String.valueOf(createTime)).getBytes("UTF-8"));
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(sha256.digest());

            String firstCap = "", firstLow = "";
            long digestNumberTotal = 0;
            String finalHex = Base64.encode(md5.digest());
            char[] digestedChars = finalHex.toCharArray();
            for (char aChar : digestedChars) {
                digestNumberTotal += (int) aChar;

                if (Character.isLetter(aChar)) {
                    if ("".equals(firstCap) && Character.isUpperCase(aChar)) {
                        firstCap = String.valueOf(aChar);
                    }

                    if ("".equals(firstLow) && Character.isLowerCase(aChar)) {
                        firstLow = String.valueOf(aChar);
                    }
                }
            }

            long currentTotal;
            while (digestNumberTotal >= 10) {
                currentTotal = 0;
                for (char digitChar : String.valueOf(digestNumberTotal).toCharArray()) {
                    currentTotal += Integer.parseInt(String.valueOf(digitChar));
                }
                digestNumberTotal = currentTotal;
            }

            uuid = firstLow + digestNumberTotal + firstCap;
            System.out.println("uuid: " + uuid);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    String uuid() {
        return uuid;
    }

    String gameName() {
        return gameName;
    }
}
