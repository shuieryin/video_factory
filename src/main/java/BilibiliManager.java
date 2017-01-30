import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class BilibiliManager {

    private static final String OUTPUT_FORMAT = "mp4";
    private static final String LOGON_URL = "https://passport.bilibili.com/login";
    private static final String CAPTCHA_IMG_PATH = "captchaImg.png";
    private static final String UPLOAD_URL = "http://member.bilibili.com/v/video/submit.html";
    private static final String APPEND_UPLOAD_URL = "http://member.bilibili.com/v/#!/article";
    private static long expireTime;
    private static int CLIP_AMOUNT_PER_BATCH = 3;
    @SuppressWarnings("FieldCanBeLocal")
    private static int OVERLAP_DURATION_SECONDS = 3;
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    // private static Pattern existingProcessedPartPattern = Pattern.compile("/part(\\d+)\\." + OUTPUT_FORMAT + "$");
    private static final String vidPathPatternStr = "/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20); // 1024 * 1024 * 50; // (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20)
    private static Pattern filesizePattern = Pattern.compile("(\\d+)");
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final String replaceSpace = "\\s";
    private static Pattern uploadingPattern = Pattern.compile("Uploading|上传中断");
    private static Pattern uploadCompletePattern = Pattern.compile("Upload\\scomplete!");

    private static final int WIDTH_SIZE = 1080;
    private static final int CRF = 5;
    private static final int AUDIO_BIT_RATE = 190;
    private static final int BIT_RATE = 8000;
    // private static final int FPS = 60;

    private String uid;
    private WebDriver driver;
    private WebDriverWait wait;
    Thread uploadThread;
    private Map<String, ProcessedGame> processedGames = new LinkedHashMap<>();

    BilibiliManager(String Uid) throws InterruptedException, IOException {
        this.uid = Uid;

        System.out.println("launching firefox browser for [" + Uid + "]");

        updateExpireTime();

        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 10);
        driver.navigate().to(LOGON_URL);

        initUploadVideo();
    }

    private boolean isLoggedOnForUpload() {
        System.out.println("driver.getCurrentUrl(): " + driver.getCurrentUrl());
        if (!APPEND_UPLOAD_URL.equalsIgnoreCase(driver.getCurrentUrl())) {
            driver.navigate().to(APPEND_UPLOAD_URL);
        }
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("footer-wrp")));

        return driver.findElements(By.className("search-wrp")).size() > 0;
    }

    String uploadVideos() throws IOException, InterruptedException, AWTException {
        if (null != uploadThread && !uploadThread.isAlive()) {
            System.out.println("cleaning upload thread");
            uploadThread = null;
        }

        String status = "";
        if (null != uploadThread && uploadThread.isAlive()) {
            status = "existing_video_being_uploaded";
        } else if (processedGames.isEmpty()) {
            status = "no_processed_vids";
        } else if (!isLoggedOnForUpload()) {
            status = "please_login_bilibili";
        }

        if (!Strings.isNullOrEmpty(status)) {
            return status;
        }

        status = "bilibili_upload_started";
        uploadThread = new Thread(() -> {
            System.out.println("processedGames size: " + processedGames.size());
            for (String processedGameName : processedGames.keySet()) {
                ProcessedGame processedGame = processedGames.get(processedGameName);

                int finalClipCount;
                int uploadedClipCount;
                int uploadingCount;
                boolean isGameProcessed;

                driver.navigate().to(APPEND_UPLOAD_URL);
                WebElement searchSection = wait.until(ExpectedConditions.presenceOfElementLocated(By.className("search-wrp")));
                CommonUtils.scrollToElement(driver, searchSection);
                WebElement searchStory = searchSection.findElement(By.tagName("input"));
                searchStory.clear();
                searchStory.sendKeys(processedGame.gameName() + Keys.ENTER);

                boolean isNewStory = false;
                try {
                    WebElement existingStoryLink = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("a[class=\"edit item\"]")));
                    String editLink = existingStoryLink.getAttribute("href");
                    driver.navigate().to(editLink);
                    CommonUtils.wait(1000, driver);
                } catch (Exception e) {
                    driver.navigate().to(UPLOAD_URL);
                    wait.until(ExpectedConditions.presenceOfElementLocated(By.className("home-hint")));
                    isNewStory = true;
                }

                Set<String> addedToUploadClips = new HashSet<>();
                do {
                    finalClipCount = 0;
                    uploadedClipCount = 0;
                    uploadingCount = 0;
                    isGameProcessed = true;

                    Map<String, ProcessedVideo> processedVideos = processedGame.processedVideos();
                    for (ProcessedVideo processedVideo : processedVideos.values()) {
                        int curClipCount = parseClipCount(processedVideo);

                        if (curClipCount == 0) {
                            isGameProcessed = false;
                        }

                        finalClipCount += curClipCount;
                    }

                    int[] uploadStatus = trackUploadStatus(uploadingCount, uploadedClipCount);
                    uploadingCount = uploadStatus[0];
                    uploadedClipCount = uploadStatus[1];

                    for (String key : processedVideos.keySet()) {
                        ProcessedVideo processedVideo = processedVideos.get(key);

                        if (processedVideo.clipPaths().isEmpty()) {
                            continue;
                        }

                        for (String targetUploadClipPath : processedVideo.clipPaths()) {
                            if (addedToUploadClips.contains(targetUploadClipPath)) {
                                continue;
                            }

                            if (uploadingCount < CLIP_AMOUNT_PER_BATCH) {
                                System.out.println();
                                System.out.println("Tracking upload status...");
                                System.out.println("finalClipCount: " + finalClipCount);
                                System.out.println("uploadingCount: " + uploadingCount);
                                System.out.println("uploadedClipCount: " + uploadedClipCount);
                                System.out.println("targetUploadClipPath: " + targetUploadClipPath);
                                System.out.println("Tracked");
                                System.out.println();
                                WebElement uploadInput = driver.findElement(By.cssSelector("input[accept=\".flv, .mp4\"]"));
                                CommonUtils.scrollToElement(driver, uploadInput);
                                uploadInput.sendKeys(targetUploadClipPath);
                                addedToUploadClips.add(targetUploadClipPath);
                                uploadingCount++;
                                CommonUtils.wait(2000, driver);
                            }
                        }

                        CommonUtils.wait(3000, driver);

                        // submitMoreButton.click();
                    }

                    CommonUtils.wait(5000, driver);
                } while (!isGameProcessed || uploadingCount > 0); // || finalClipCount != uploadedClipCount

                if (isNewStory) {
//                    WebElement uploadInput = driver.findElement(By.cssSelector("input[accept=\".flv, .mp4\"]"));
//                    CommonUtils.scrollToElement(driver, uploadInput);
//                    uploadInput.sendKeys(ManageServer.ROOT_PATH + "mock.mp4");
//
//                    try {
//                        WebElement dataAlertHideButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("button[data-alert-hide]")));
//                        CommonUtils.scrollToElement(driver, dataAlertHideButton);
//                        dataAlertHideButton.click();
//                    } catch (Exception e) {
//                        System.out.println("No data alert hide button");
//                    }
//
//                    WebElement delIcon = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("i[class=\"icon icon-cancel\"]")));
//                    CommonUtils.scrollToTop(driver);
//                    Actions action = new Actions(driver);
//                    action.moveToElement(delIcon).build().perform();
//                    CommonUtils.wait(500, driver);
//                    // CommonUtils.scrollToElement(driver, delIcon);
//                    delIcon.click();
//
//                    WebElement delButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("button[data-del-upload]")));
//                    CommonUtils.scrollToElement(driver, delButton);
//                    delButton.click();

                    WebElement selfMadeRadio = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("input[name=\"copyright\"]")));
                    CommonUtils.scrollToElement(driver, selfMadeRadio);
                    selfMadeRadio.click();

                    WebElement categorySection = driver.findElement(By.cssSelector("ul[class=\"type-menu clearfix\"]"));
                    CommonUtils.scrollToElement(driver, categorySection);

                    List<WebElement> categoryElements = categorySection.findElements(By.cssSelector("*"));

                    for (WebElement curElement : categoryElements) {
                        String innerHTML = curElement.getAttribute("innerHTML");
                        if ("Gaming".equals(innerHTML)) {
                            curElement.click();
                        }

                        if ("Stand-alone/Online Games".equals(innerHTML)) {
                            curElement.findElement(By.xpath("..")).click();
                            break;
                        }
                    }

                    WebElement titleField = driver.findElement(By.cssSelector("input[placeholder=\"Please enter the submission title\"]"));
                    CommonUtils.scrollToElement(driver, titleField);
                    titleField.clear();
                    titleField.sendKeys(processedGame.gameName());

                    WebElement tagsSection = driver.findElement(By.cssSelector("div[class=\"recommend-wrp\"]"));
                    WebElement tagsField = tagsSection.findElement(By.tagName("input"));
                    CommonUtils.scrollToElement(driver, tagsField);
                    for (String tag : processedGame.gameName().split(" ")) {
                        tagsField.sendKeys(tag + Keys.ENTER);
                    }
                    tagsField.sendKeys(processedGame.gameName() + Keys.ENTER);

                    WebElement descSection = driver.findElement(By.className("description-wrp"));
                    WebElement descField = descSection.findElement(By.tagName("textarea"));
                    CommonUtils.scrollToElement(driver, descField);
                    descField.sendKeys(processedGame.gameName());
                }

                WebElement submitButton = driver.findElement(By.cssSelector("button[class=\"btn submit-btn\"]"));
                do {
                    CommonUtils.scrollToElement(driver, submitButton);
                    submitButton.click();
                    CommonUtils.wait(5000, driver);
                    System.out.println("submit button is displayed");
                } while (submitButton.isDisplayed());

                // wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("a[href=\"" + UPLOAD_URL + "\"]")));

                for (ProcessedVideo processedVideo : processedGame.processedVideos().values()) {
                    processedVideo.uploadDone();
                }

                processedGames.remove(processedGameName);
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
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("footer-wrp")));

        return isLoggedOnForUpload();
    }

    private boolean inputCredentials(String username, String password, boolean isReopenUrl) throws IOException, InterruptedException, AWTException {
        if (isReopenUrl) {
            driver.navigate().to(LOGON_URL);
        }

        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("footer-wrp")));
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
        WebElement captchaImg = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("captchaImg")));
        CommonUtils.wait(500, driver);

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
                if (0 == totalSeconds) {
                    continue;
                }

                long clipCount = 0;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("vidPath not found: " + vidPath);
                    continue;
                }
                String gameName = vidPathMatcher.group(2);

                ProcessedGame processedGame = processedGames.get(gameName);
                if (null == processedGame) {
                    processedGame = new ProcessedGame(gameName);
                    processedGames.put(gameName, processedGame);
                }

                Map<String, ProcessedVideo> processedVideos = processedGame.processedVideos();
                ProcessedVideo processedVideo = processedVideos.get(vidPath);
                if (null == processedVideo) {
                    processedVideo = new ProcessedVideo(vidPath, vidPathPattern);
                    String initParsedVidPath = parsedVidPath + ".done." + clipCount;
                    processedVideo.setOriginalVideoPath(initParsedVidPath);
                    processedVideos.put(vidPath, processedVideo);
                    processedGame.addProcessedVideo(vidPath, processedVideo);
                }

                long startPos = 0;
                String lastProcessedClipPath;
                do {
                    lastProcessedClipPath = processedVideo.processedPath + processedVideo.uuid() + "-" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String command = "ffmpeg -y -i " + parsedVidPath
                            + " -ss " + startPos
                            // + " -r " + FPS
                            + " -b " + BIT_RATE + "k"
                            + " -minrate " + BIT_RATE + "k"
                            + " -maxrate " + BIT_RATE + "k"
                            + " -bufsize " + BIT_RATE + "k"
                            + " -c:a aac -strict -2 -b:a " + AUDIO_BIT_RATE + "k"
                            + " -vf scale=w=-1:h=" + WIDTH_SIZE + ":force_original_aspect_ratio=decrease"
                            + " -codec:v libx264"
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

                System.out.println(processedVideo.gameName() + " total vidSeconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");
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

        Collections.sort(vidsList);

        return vidsList;
    }

    private long videoDuration(String vidPath) {
        String timeOutput = ManageServer.executeCommand("ffmpeg -i " + vidPath + " 2>&1 | grep Duration | grep -oP \"^\\s*Duration:\\s*\\K(\\S+),\" | cut -c 1-11");

        Matcher timerMatcher = timePattern.matcher(timeOutput);
        if (!timerMatcher.find()) {
            return 0;
        }
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
            if (!vidPathMatcher.find()) {
                continue;
            }

            String gameName = vidPathMatcher.group(2);
            ProcessedGame processedGame = processedGames.get(gameName);
            if (null == processedGame) {
                processedGame = new ProcessedGame(gameName);
                processedGames.put(gameName, processedGame);
            }

            ProcessedVideo processedVideo = new ProcessedVideo(pendingUploadVid, processedVidPathPattern);
            processedVideo.setOriginalVideoPath(pendingUploadVid.replaceAll(replaceSpace, "\\\\ "));

            int clipCount = Integer.parseInt(vidPathMatcher.group(11));
            String uploadRootPath = processedVideo.processedPath.replaceAll("\\\\", "");
            for (int i = 0; i < clipCount; i++) {
                processedVideo.addClipPath(uploadRootPath + processedVideo.uuid() + "-" + (i + 1) + "." + OUTPUT_FORMAT);
            }

            processedGame.addProcessedVideo(pendingUploadVid, processedVideo);
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

    private int[] trackUploadStatus(int uploadingCount, int uploadedClipCount) {
        Elements uploadStatuses;
        try {
            do {
                WebElement uploadList = driver.findElement(By.id("sortWrp"));
                Document deviceTableDom = Jsoup.parse(uploadList.getAttribute("innerHTML"));
                uploadStatuses = deviceTableDom.getElementsByClass("upload-status");
                CommonUtils.wait(5000, driver);
            } while (uploadStatuses.isEmpty());

            for (Element uploadStatus : uploadStatuses) {
                String statusStr = uploadStatus.html();
                if (null == statusStr) {
                    System.out.println("statusStr is null");
                    continue;
                }

                if (uploadingPattern.matcher(statusStr).find()) {
                    uploadingCount++;
                } else if (uploadCompletePattern.matcher(statusStr).find()) {
                    uploadedClipCount++;
                }
            }
        } catch (StaleElementReferenceException e) {
            CommonUtils.wait(5000, driver);
            System.out.println("Lost statuses, retrying again");
            trackUploadStatus(uploadingCount, uploadedClipCount);
        } catch (Exception e) {
            System.out.println("Tracking status error:");
            e.printStackTrace();
        }

        //noinspection UnnecessaryLocalVariable
        int[] ret = {uploadingCount, uploadedClipCount};
        return ret;
    }
}