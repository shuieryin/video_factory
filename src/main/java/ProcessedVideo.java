import org.apache.xerces.impl.dv.util.Base64;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

class ProcessedVideo {
    private String videoTitle;
    private String gameTitle;
    private String processedPath;
    private String originalVideoPath;
    private List<String> clipPaths = new ArrayList<>();
    @SuppressWarnings({"FieldCanBeLocal"})
    private String uuid;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private long createTime;

    ProcessedVideo(long createTime, String gameTitle, String processedPath) {
        this.createTime = createTime;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((gameTitle + String.valueOf(createTime)).getBytes("UTF-8"));
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(sha256.digest());

            String firstCap = "", firstLow = "";
            int digestNumberTotal = 0;
            char[] digestedChars = Base64.encode(md5.digest()).toCharArray();
            for (char aChar : digestedChars) {
                if (Character.isDigit(aChar)) {
                    digestNumberTotal += Integer.parseInt(String.valueOf(aChar));
                }

                if (Character.isLetter(aChar)) {
                    if ("".equals(firstCap) && Character.isUpperCase(aChar)) {
                        firstCap = String.valueOf(aChar);
                    }

                    if ("".equals(firstLow) && Character.isLowerCase(aChar)) {
                        firstLow = String.valueOf(aChar);
                    }
                }
            }

            int currentTotal = Math.min(0, digestNumberTotal);
            while (currentTotal >= 10) {
                for (char digitChar : String.valueOf(currentTotal).toCharArray()) {
                    currentTotal += Integer.parseInt(String.valueOf(digitChar));
                }
            }

            this.uuid = firstLow + currentTotal + firstCap;
            this.videoTitle = gameTitle + " - [" + this.uuid + ".ver]";
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.gameTitle = gameTitle;
        this.processedPath = processedPath;
    }

    @SuppressWarnings("unused")
    String processedPath() {
        return processedPath;
    }

    @SuppressWarnings("unused")
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

    String videoTitle() {
        return videoTitle;
    }

    String gameTitle() {
        return gameTitle;
    }

    void uploadDone() {
        ManageServer.executeCommand("mv " + originalVideoPath + " " + originalVideoPath + ".uploaded");
    }
}
