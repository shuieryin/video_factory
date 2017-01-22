import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.openqa.selenium.*;
import org.openqa.selenium.Point;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class BilibiliManager {

    private static final String OUTPUT_FORMAT = "flv";
    private static final String LOGON_URL = "https://passport.bilibili.com/login";
    private static final String CAPTCHA_IMG_PATH = "captchaImg.png";
    private static final String UPLOAD_URL = "http://member.bilibili.com/v/video/submit.html";
    private static long expireTime;
    private static int CLIP_AMOUNT_PER_BATCH = 3;
    @SuppressWarnings("FieldCanBeLocal")
    private static int OVERLAP_DURATION_SECONDS = 3;
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static final String vidPathPatternStr = "/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20); // 1024 * 1024 * 50; // (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20)
    private static final int WIDTH_SIZE = 810;
    private static final int CRF = 13;
    private static Pattern filesizePattern = Pattern.compile("(\\d+)");
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final String replaceSpace = "\\s";
    private static Pattern uploadingPattern = Pattern.compile("Uploading|上传中断");
    private static Pattern uploadCompletePattern = Pattern.compile("Upload\\scomplete!");

    private String uid;
    private WebDriver driver;
    private WebDriverWait wait;
    Thread uploadThread;
    private Map<String, ProcessedVideo> processedVideos = new HashMap<>();

    BilibiliManager(String Uid) throws InterruptedException, IOException {
        this.uid = Uid;

        System.out.println("launching firefox browser for [" + Uid + "]");

        updateExpireTime();

        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 20);
        driver.navigate().to(LOGON_URL);

        initUploadVideo();
    }

    private boolean isLoggedOnForUpload() {
        if (!UPLOAD_URL.equalsIgnoreCase(driver.getCurrentUrl())) {
            driver.navigate().to(UPLOAD_URL);
        }
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("footer-wrp")));

        return driver.findElements(By.className("home-hint")).size() > 0;
    }

    String uploadVideos() throws IOException, InterruptedException, AWTException {
        if (null != uploadThread && !uploadThread.isAlive()) {
            System.out.println("cleaning upload thread");
            uploadThread = null;
        }

        String status = "";
        if (null != uploadThread && uploadThread.isAlive()) {
            status = "existing_video_being_uploaded";
        } else if (processedVideos.isEmpty()) {
            status = "no_processed_vids";
        } else if (!isLoggedOnForUpload()) {
            status = "please_login_bilibili";
        }

        if (!Strings.isNullOrEmpty(status)) {
            return status;
        }

        status = "bilibili_upload_started";
        uploadThread = new Thread(() -> {
            for (String key : processedVideos.keySet()) {
                ProcessedVideo processedVideo = processedVideos.get(key);
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("home-hint")));

                int finalClipCount = parseClipCount(processedVideo);
                int uploadedClipCount = 0;
                Set<String> existingClips = new HashSet<>();
                while (finalClipCount == 0 || finalClipCount != uploadedClipCount) {
                    System.out.println();
                    System.out.println("Tracking upload status...");
                    try {
                        int uploadingCount = 0;
                        uploadedClipCount = 0;
                        List<WebElement> uploadsStatuses = driver.findElements(By.className("upload-status"));
                        System.out.println("uploadsStatuses size: " + uploadsStatuses.size());
                        for (WebElement uploadStatus : uploadsStatuses) {
                            String statusStr = uploadStatus.getText();
                            if (null == statusStr) {
                                System.out.println("statusStr is null");
                                continue;
                            }
                            if (uploadingPattern.matcher(statusStr).find()) {
                                uploadingCount++;
                            }

                            if (uploadCompletePattern.matcher(statusStr).find()) {
                                uploadedClipCount++;
                            }
                        }

                        System.out.println("finalClipCount: " + finalClipCount);
                        System.out.println("uploadingCount: " + uploadingCount);
                        System.out.println("uploadedClipCount: " + uploadedClipCount);

                        for (String targetUploadClipPath : processedVideo.clipPaths()) {
                            if (existingClips.contains(targetUploadClipPath)) {
                                continue;
                            }

                            if (uploadingCount < CLIP_AMOUNT_PER_BATCH) {
                                WebElement uploadInput = driver.findElement(By.cssSelector("input[accept=\".flv, .mp4\"]"));
                                uploadInput.sendKeys(targetUploadClipPath);
                                existingClips.add(targetUploadClipPath);
                                uploadingCount++;
                                TimeUnit.SECONDS.sleep(2);
                            }
                        }

                        finalClipCount = parseClipCount(processedVideo);
                    } catch (Exception e) {
                        System.out.println();
                        System.out.println("Errors during tracking update status:");
                        e.printStackTrace();
                        System.out.println();
                    }

                    System.out.println("Tracked");
                    System.out.println();
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                WebElement selfMadeRadio = driver.findElement(By.cssSelector("input[name=\"copyright\"]"));
                selfMadeRadio.click();

                WebElement categorySection = driver.findElement(By.cssSelector("ul[class=\"type-menu clearfix\"]"));

                List<WebElement> categoryElements = categorySection.findElements(By.cssSelector("*"));

                for (WebElement curElement : categoryElements) {
                    String innerHTML = curElement.getText();
                    if ("Gaming".equals(innerHTML)) {
                        curElement.click();
                    }

                    if ("Stand-alone/Online Games".equals(innerHTML)) {
                        curElement.findElement(By.xpath("..")).click();
                        break;
                    }
                }

                WebElement titleField = driver.findElement(By.cssSelector("input[placeholder=\"Please enter the submission title\"]"));
                titleField.clear();
                titleField.sendKeys(processedVideo.videoTitle());

                WebElement tagsField = driver.findElement(By.cssSelector("input[placeholder=\"Press enter to finish.\"]"));
                tagsField.sendKeys(processedVideo.gameTitle());
                tagsField.sendKeys(Keys.ENTER);

                WebElement descField = driver.findElement(By.cssSelector("textarea[placeholder=\"Proper description is beneficial to submission approval, and promotes the occurrence frequency in category and searching.\"]"));
                descField.sendKeys(processedVideo.videoTitle());

                WebElement submitButton = driver.findElement(By.cssSelector("button[class=\"btn submit-btn\"]"));
                submitButton.click();

                WebElement submitMoreButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a[href=\"" + UPLOAD_URL + "\"]")));

                processedVideo.uploadDone();

                submitMoreButton.click();

                processedVideos.remove(key);
            }
        });

        uploadThread.start();

        return status;
    }

    private boolean tapLogon(String inputCaptcha) {
        WebElement vdCodeField = driver.findElement(By.id("vdCodeTxt"));
        vdCodeField.clear();
        vdCodeField.sendKeys(inputCaptcha);

        WebElement headDOM = driver.findElement(By.className("head_foot_content"));
        WebElement logonForm = headDOM.findElement(By.tagName("form"));
        logonForm.submit();

        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("vdCodeTxt")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("footer-wrp")));

        return isLoggedOnForUpload();
    }

    private boolean inputCredentials(String username, String password, boolean isReopenUrl) throws IOException, InterruptedException, AWTException {
        if (isReopenUrl) {
            driver.navigate().to(LOGON_URL);
        }

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("footer-wrp")));
        if (driver.findElements(By.id("b_live")).size() > 0) {
            return true;
        }

        WebElement vdCodeField = driver.findElement(By.id("vdCodeTxt"));
        vdCodeField.clear();

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("$(\"#captchaImg\").attr(\"src\", \"/captcha\");$('#captchaImg').show();");

        WebElement usernameField = driver.findElement(By.name("userid"));
        usernameField.clear();
        usernameField.sendKeys(username);

        WebElement passwordField = driver.findElement(By.name("pwd"));
        passwordField.clear();
        passwordField.sendKeys(password);

        return false;
    }

    private File captchaImage() throws IOException {
        WebElement captchaImg = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("captchaImg")));
        driver.manage().timeouts().implicitlyWait(500L, TimeUnit.MICROSECONDS);

        File captchaImgFile = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        FileUtils.copyFile(captchaImgFile, new File(CAPTCHA_IMG_PATH));

        BufferedImage fullPageImg = ImageIO.read(captchaImgFile);

        int height = captchaImg.getSize().height;
        int width = captchaImg.getSize().width;

        Rectangle rect = new Rectangle(width, height);
        Point p = captchaImg.getLocation();
        BufferedImage dest = fullPageImg.getSubimage(p.getX(), p.getY(), rect.width, rect.height);
        ImageIO.write(dest, "png", captchaImgFile);
        FileUtils.copyFile(captchaImgFile, new File(CAPTCHA_IMG_PATH));

        return captchaImgFile;
    }

    void close() {
        if (driver != null) {
            driver.quit();
            System.out.println("session: " + uid + " closed.");
        }
    }

    long expireTime() {
        return expireTime;
    }

    void updateExpireTime() {
        expireTime = Calendar.getInstance().getTimeInMillis() + 1000 * 60 * 30;
    }

    @SuppressWarnings("unused")
    String uid() {
        return uid;
    }

    boolean processVideos() {
        System.out.println();
        System.out.println("processing videos...");
        if (null == ManageServer.commandOut) {
            return false;
        }

        try {
            for (String vidPath : pendingProcessVids(false)) {
                System.out.println("vidPath: " + vidPath);
                String parsedVidPath = vidPath.replaceAll(replaceSpace, "\\\\ ");
                long totalSeconds = videoDuration(parsedVidPath);

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                vidPathMatcher.find();

                String videoName = vidPathMatcher.group(1);
                String gameName = vidPathMatcher.group(2);
                String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";
                ManageServer.executeCommand("mkdir -p " + processedPath + "; rm -f " + processedPath + "*");

                LocalDateTime timePoint = LocalDateTime.of(
                        Integer.parseInt(vidPathMatcher.group(3)),
                        Integer.parseInt(vidPathMatcher.group(4)),
                        Integer.parseInt(vidPathMatcher.group(5)),
                        Integer.parseInt(vidPathMatcher.group(6)),
                        Integer.parseInt(vidPathMatcher.group(7)),
                        Integer.parseInt(vidPathMatcher.group(8))
                );

                long clipCount = 0;
                long videoCreateTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
                ProcessedVideo processedVideo = new ProcessedVideo(videoCreateTime, gameName, processedPath);
                String initParsedVidPath = parsedVidPath + ".done." + clipCount;
                processedVideo.setOriginalVideoPath(initParsedVidPath);
                processedVideos.put(gameName, processedVideo);

                long startPos = 0;
                String lastProcessedClipPath;
                do {
                    lastProcessedClipPath = processedPath + "part" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String command = "ffmpeg -i " + parsedVidPath
                            + " -ss " + startPos
                            + " -vf scale=w=-1:h=" + WIDTH_SIZE + ":force_original_aspect_ratio=decrease"
                            + " -codec:v libx264"
                            + " -ar 44100"
                            + " -crf " + CRF
                            + " -fs " + LIMIT_SIZE_BYTES
                            + " " + lastProcessedClipPath;

                    ManageServer.executeCommandRemotely(command, true);

                    System.out.println();
                    System.out.println("startPos: " + startPos);
                    System.out.println("totalSeconds: " + totalSeconds);
                    System.out.println();

                    startPos += videoDuration(lastProcessedClipPath) - OVERLAP_DURATION_SECONDS;
                    String rawFileSize = ManageServer.executeCommand("ls -l " + lastProcessedClipPath + " | grep -oP \"^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\K(\\S+)\" | tr -d '\\n'");
                    Matcher fileSizeMatcher = filesizePattern.matcher(rawFileSize);
                    fileSizeMatcher.find();
                    System.out.println("startPos: " + startPos);
                    processedVideo.addClipPath(lastProcessedClipPath.replaceAll("\\\\ ", " "));
                } while (startPos < totalSeconds - OVERLAP_DURATION_SECONDS);

                System.out.println();
                System.out.println("startPos: " + startPos);
                System.out.println("totalSeconds: " + totalSeconds);
                System.out.println();

                String finalParsedVidPath = parsedVidPath + ".done." + clipCount;
                processedVideo.setOriginalVideoPath(finalParsedVidPath);
                ManageServer.executeCommand("mv " + parsedVidPath + " " + finalParsedVidPath);

                System.out.println(gameName + " total vidSeconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("process videos done");
        System.out.println();
        return true;
    }

    private List<String> pendingProcessVids(boolean isGetDones) throws IOException {
        List<String> vidsList = new ArrayList<>();

        Path rootPath = Paths.get("/root/vids/pending_process");

        if (!Files.exists(rootPath)) {
            return vidsList;
        }

        Stream<Path> gamePaths = Files.walk(rootPath);
        gamePaths.forEach(gamePath -> {
            if (Files.isDirectory(gamePath) && !gamePath.toString().equals(rootPath.toString())) {
                try (Stream<Path> vidsPath = Files.walk(gamePath)) {
                    vidsPath.forEach(vidPath -> {
                        if (Files.isDirectory(vidPath)) {
                            return;
                        }

                        String vidPathStr = vidPath.toString();
                        Matcher matcher = isGetDones ? processedVidPattern.matcher(vidPathStr) : vidPathPattern.matcher(vidPathStr);
                        if (matcher.find()) {
                            vidsList.add(vidPathStr);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        return vidsList;
    }

    private long videoDuration(String vidPath) {
        String timeOutput = ManageServer.executeCommand("ffmpeg -i " + vidPath + " 2>&1 | grep Duration | grep -oP \"^\\s*Duration:\\s*\\K(\\S+),\" | cut -c 1-11");

        Matcher timerMatcher = timePattern.matcher(timeOutput);
        timerMatcher.find();
        int vidHour = Integer.parseInt(timerMatcher.group(1));
        int vidMinutes = Integer.parseInt(timerMatcher.group(2));
        int vidSeconds = Integer.parseInt(timerMatcher.group(3));
        return 1 + vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;
    }

    private void latestCaptcha(JSONObject returnJSON) throws IOException {
        File captchaImage = captchaImage();
        System.out.println(captchaImage);

        InputStream captchaImageIn = new FileInputStream(captchaImage);
        byte[] captchaImageBytes = IOUtils.toByteArray(captchaImageIn);

        returnJSON.put("event", "input_captcha");
        returnJSON.put("is_logged_on", false);
        returnJSON.put("captcha_image_bytes", org.apache.xerces.impl.dv.util.Base64.encode(captchaImageBytes));
    }

    void stopUploadThread() {
        if (null != uploadThread && uploadThread.isAlive()) {
            //noinspection deprecation
            uploadThread.stop();
            uploadThread = null;
        }
    }

    private void initUploadVideo() throws IOException {
        System.out.println();
        System.out.println("initUploadVideo start");
        List<String> pendingUploadVids = pendingProcessVids(true);
        System.out.println("pendingUploadVids size: " + pendingUploadVids.size());

        for (String pendingUploadVid : pendingUploadVids) {
            System.out.println("init pending upload vid: " + pendingUploadVid);
            Matcher vidPathMatcher = processedVidPathPattern.matcher(pendingUploadVid);
            vidPathMatcher.find();

            String videoName = vidPathMatcher.group(1);
            String gameName = vidPathMatcher.group(2);

            String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";

            LocalDateTime timePoint = LocalDateTime.of(
                    Integer.parseInt(vidPathMatcher.group(3)),
                    Integer.parseInt(vidPathMatcher.group(4)),
                    Integer.parseInt(vidPathMatcher.group(5)),
                    Integer.parseInt(vidPathMatcher.group(6)),
                    Integer.parseInt(vidPathMatcher.group(7)),
                    Integer.parseInt(vidPathMatcher.group(8))
            );

            long videoCreateTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
            ProcessedVideo processedVideo = new ProcessedVideo(videoCreateTime, gameName, processedPath);
            processedVideo.setOriginalVideoPath(pendingUploadVid.replaceAll(replaceSpace, "\\\\ "));

            int clipCount = Integer.parseInt(vidPathMatcher.group(11));
            String uploadRootPath = processedPath.replaceAll("\\\\", "");
            for (int i = 0; i < clipCount; i++) {
                processedVideo.addClipPath(uploadRootPath + "part" + (i + 1) + "." + OUTPUT_FORMAT);
            }

            processedVideos.put(gameName, processedVideo);
        }
        System.out.println();
    }

    String handleUserInput(Map<String, List<String>> getParams) {
        String returnContent = "";
        try {
            String uid = getParams.get("uid").get(0);

            JSONObject returnJSON = new JSONObject();
            returnJSON.put("uid", uid);
            String event = getParams.get("event").get(0);
            switch (event) {
                case "init_browser_session":
                    updateExpireTime();
                    break;
                case "close_browser_session":
                    close();
                    ManageServer.bilibiliManagersMap.remove(uid);
                    break;
                case "input_captcha":
                    String inputCaptcha = getParams.get("input_captcha").get(0);
                    boolean isLogonSuccess = tapLogon(inputCaptcha);
                    returnJSON.put("status", isLogonSuccess);
                    break;
                case "input_credentials":
                    String username = getParams.get("username").get(0);
                    String password = getParams.get("password").get(0);
                    boolean isReopenUrl = "true".equals(getParams.get("is_reopen_url").get(0));
                    if (inputCredentials(username, password, isReopenUrl)) {
                        returnJSON.put("is_logged_on", true);
                    } else {
                        latestCaptcha(returnJSON);
                    }
                    break;
                case "pending_process_vids":
                    returnJSON.put("vids_list", pendingProcessVids(false));
                    break;
                case "get_latest_captcha":
                    latestCaptcha(returnJSON);
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids(true));
                    break;
                case "upload_vids":
                    returnJSON.put("status", uploadVideos());
                    break;
            }

            returnJSON.toMap().forEach((key, value) -> System.out.println(key + "=" + value));
            System.out.println();

            returnContent = returnJSON.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return returnContent;
    }

    private int parseClipCount(ProcessedVideo processedVideo) {
        Matcher vidPathMatcher = processedVidPathPattern.matcher(processedVideo.originalVideoPath().replaceAll("\\\\ ", " "));
        vidPathMatcher.find();
        return Integer.parseInt(vidPathMatcher.group(11));
    }
}