import org.apache.xerces.impl.dv.util.Base64;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ProcessedVideo {
    private String videoName;
    private String gameName;
    String processedPath;
    private String originalVideoPath;
    private List<String> clipPaths = new ArrayList<>();
    private String uuid;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private long createTime;

    ProcessedVideo(String vidPath, Pattern pattern) {
        Matcher vidPathMatcher = pattern.matcher(vidPath);
        vidPathMatcher.find();

        videoName = vidPathMatcher.group(1);
        gameName = vidPathMatcher.group(2);
        processedPath = "/root/vids/processed/" + gameName.replaceAll("\\s", "\\\\ ") + "/"; // + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";;
        ManageServer.executeCommand("mkdir -p " + processedPath); // + "; rm -f " + processedPath + "*");

        LocalDateTime timePoint = LocalDateTime.of(
                Integer.parseInt(vidPathMatcher.group(3)),
                Integer.parseInt(vidPathMatcher.group(4)),
                Integer.parseInt(vidPathMatcher.group(5)),
                Integer.parseInt(vidPathMatcher.group(6)),
                Integer.parseInt(vidPathMatcher.group(7)),
                Integer.parseInt(vidPathMatcher.group(8))
        );
        createTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((gameName + String.valueOf(createTime)).getBytes("UTF-8"));
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(sha256.digest());

            String firstCap = "", firstLow = "";
            long digestNumberTotal = 0;
            String finalHex = Base64.encode(md5.digest());
            System.out.println(finalHex);
            char[] digestedChars = finalHex.toCharArray();
            for (char aChar : digestedChars) {
                int charNum = (int) aChar;
                System.out.println("charNum: " + charNum);
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

            long currentTotal = 0;
            System.out.println("currentTotal: " + digestNumberTotal);
            while (digestNumberTotal >= 10) {
                currentTotal = 0;
                for (char digitChar : String.valueOf(digestNumberTotal).toCharArray()) {
                    currentTotal += Integer.parseInt(String.valueOf(digitChar));
                }
                digestNumberTotal = currentTotal;
            }
            System.out.println("finalcurrentTotal: " + currentTotal);

            uuid = firstLow + digestNumberTotal + firstCap;
            System.out.println("vidPath: " + vidPath);
            System.out.println("uuid: " + uuid);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    String originalVideoPath() {
        return originalVideoPath;
    }

    List<String> clipPaths() {
        return clipPaths;
    }

    void addClipPath(String clipPath) {
        clipPaths.add(clipPath);
    }

    void setOriginalVideoPath(String originalVideoPath) {
        this.originalVideoPath = originalVideoPath;
    }

    String uuid() { return uuid; }

    void uploadDone() {
        ManageServer.executeCommand("mv " + originalVideoPath + " " + originalVideoPath + ".uploaded");
    }

    String videoName() {
        return videoName;
    }

    String gameName() {
        return gameName;
    }
}
