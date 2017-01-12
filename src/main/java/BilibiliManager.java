import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class BilibiliManager {

    private static final String LOGON_URL = "https://passport.bilibili.com/login";
    private static final String CAPTCHA_IMG_PATH = "captchaImg.png";
    private static final String UPLOAD_URL = "http://member.bilibili.com/v/video/submit.html";
    private static final String GATEWAY_URL = "http://192.168.1.1/";
    private static long expireTime;
    private static List<String> tabs;
    private static final int BILIBILI_TAB = 0;
    private static final int ROUTER_TAB = 1;
    private static final int MAX_UPLOAD_SPEED = 650;

    private String uid;
    private WebDriver driver;
    private WebDriverWait wait;
    private Pattern speedPattern = Pattern.compile("(\\d+)(KB|MB|GB)/s");

    BilibiliManager(String Uid) throws InterruptedException {
        this.uid = Uid;

        System.out.println("launching firefox browser");

        updateExpireTime();

        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 20);
        driver.navigate().to(LOGON_URL);

        WebElement newLink = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a[href=\"//www.bilibili.com/html/friends-links.html\"]")));
        newLink.click();

        while ((tabs = new ArrayList<>(driver.getWindowHandles())).size() == 1) {
            TimeUnit.MILLISECONDS.sleep(500);
        }

        driver.switchTo().window(tabs.get(BILIBILI_TAB));

        BilibiliManager self = this;
        BilibiliManageServer.scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        self.balanceUploadSpeed();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                },
                10,
                60,
                TimeUnit.SECONDS
        );
    }

    boolean isLoggedOnForUpload() {
        driver.navigate().to(UPLOAD_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("footer-wrp")));

        return driver.findElements(By.className("home-hint")).size() > 0;
    }

    Thread uploadVideos(Map<String, ProcessedVideo> processedVideos) throws IOException, InterruptedException, AWTException {
        Thread uploadThread = new Thread(() -> {
            try {
                driver.switchTo().window(tabs.get(BILIBILI_TAB));
                if (!UPLOAD_URL.equalsIgnoreCase(driver.getCurrentUrl())) {
                    driver.navigate().to(UPLOAD_URL);
                }

                for (ProcessedVideo processedVideo : processedVideos.values()) {
                    wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("home-hint")));
                    for (String targetUploadClipPath : processedVideo.clipPaths()) {
                        WebElement uploadInput = driver.findElement(By.cssSelector("input[accept=\".flv, .mp4\"]"));
                        uploadInput.sendKeys(targetUploadClipPath);

                        boolean isUploading = true;
                        while (isUploading) {
                            List<WebElement> uploadsStatus = driver.findElements(By.className("upload-status"));
                            for (WebElement uploadStatus : uploadsStatus) {
                                if (uploadStatus.getAttribute("innerHTML").contains("Uploading")) {
                                    isUploading = true;
                                    break;
                                }

                                isUploading = false;
                            }

                            TimeUnit.SECONDS.sleep(3);
                        }
                    }

                    WebElement selfMadeRadio = driver.findElement(By.cssSelector("input[name=\"copyright\"]"));
                    selfMadeRadio.click();

                    WebElement categorySection = driver.findElement(By.cssSelector("ul[class=\"type-menu clearfix\"]"));

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
                    titleField.clear();
                    titleField.sendKeys(processedVideo.videoTitle());

                    WebElement tagsField = driver.findElement(By.cssSelector("input[placeholder=\"Press enter to finish.\"]"));
                    tagsField.sendKeys(processedVideo.gameTitle());
                    tagsField.sendKeys(Keys.ENTER);

                    WebElement descField = driver.findElement(By.cssSelector("textarea[placeholder=\"Proper description is beneficial to submission approval, and promotes the occurrence frequency in category and searching.\"]"));
                    descField.sendKeys(processedVideo.videoTitle());

                    WebElement submitButton = driver.findElement(By.cssSelector("button[class=\"btn submit-btn\"]"));
                    submitButton.click();

                    WebElement submitMoreButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a[href=\"http://member.bilibili.com/v/video/submit.html\"]")));

                    processedVideo.uploadDone();

                    submitMoreButton.click();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        uploadThread.start();

        return uploadThread;
    }

    boolean tapLogon(String inputCaptcha) {
        driver.switchTo().window(tabs.get(BILIBILI_TAB));
        WebElement vdCodeField = driver.findElement(By.id("vdCodeTxt"));
        vdCodeField.clear();
        vdCodeField.sendKeys(inputCaptcha);

        WebElement headDOM = driver.findElement(By.className("head_foot_content"));
        WebElement logonForm = headDOM.findElement(By.tagName("form"));
        logonForm.submit();

        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("vdCodeTxt")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("footer-wrp")));
        return driver.findElements(By.id("b_live")).size() > 0;
    }

    boolean inputCredentials(String username, String password, boolean isReopenUrl) throws IOException, InterruptedException, AWTException {
        driver.switchTo().window(tabs.get(BILIBILI_TAB));
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

    File captchaImage() throws IOException {
        driver.switchTo().window(tabs.get(BILIBILI_TAB));
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

    void balanceUploadSpeed() throws InterruptedException, IOException {
        driver.switchTo().window(tabs.get(ROUTER_TAB));
        if (!GATEWAY_URL.equalsIgnoreCase(driver.getCurrentUrl())) {
            while (driver.findElements(By.id("pwdTipStr")).size() < 1) {
                driver.navigate().to(GATEWAY_URL);
                TimeUnit.SECONDS.sleep(3);
            }
        }

        if (driver.findElements(By.id("eptMngRCon")).size() < 1) {
            WebElement passwordField = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("pwdTipStr")));
            TimeUnit.SECONDS.sleep(1);
            passwordField.sendKeys(BilibiliManageServer.retrieveData("router_password"));

            WebElement logonButton = driver.findElement(By.id("loginSub"));
            logonButton.click();

            WebElement deviceManageButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("routeMgtMbtn")));
            deviceManageButton.click();
        }

        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");

        WebElement deviceTable = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("eptMngRCon")));
        Elements deviceInfos;
        do {
            String deviceTableHtmlStr = deviceTable.getAttribute("innerHTML");
            Document deviceTableDom = Jsoup.parse(deviceTableHtmlStr);
            deviceInfos = deviceTableDom.getElementsByClass("vs");
        } while (deviceInfos.isEmpty());

        long totalUploadKiloBytes = 0;
        long totalDownloadKiloBytes = 0;

        for (Element deviceInfo : deviceInfos) {
            List<Node> childNodes = deviceInfo.childNodes();

            String uploadSpeedStr = childNodes.get(0).toString();
            String downloadSpeedStr = childNodes.get(1).toString();

            System.out.println("uploadSpeedStr: " + uploadSpeedStr);
            System.out.println("downloadSpeedStr: " + downloadSpeedStr);

            totalUploadKiloBytes += parseByte(uploadSpeedStr, totalUploadKiloBytes);
            totalDownloadKiloBytes += parseByte(downloadSpeedStr, totalDownloadKiloBytes);

            String deviceName = deviceInfo.previousSibling().childNode(0).attr("title");
            if ("GlServer".equals(deviceName)) {
                System.out.println("this is gl server");
            }
        }

        System.out.println("totalUploadKiloBytes: " + totalUploadKiloBytes);
        System.out.println("totalDownloadKiloBytes: " + totalDownloadKiloBytes);

        clickManage();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("eptMngDetail")));

        long targetUploadSpeedLimit = Math.max(10, MAX_UPLOAD_SPEED - totalUploadKiloBytes);
        System.out.println("targetUploadSpeedLimit: " + targetUploadSpeedLimit);
        setLimitUpload(targetUploadSpeedLimit);
    }

    private void setLimitUpload(long targetUploadSpeedLimit) throws InterruptedException {
        try {
            WebElement existingLimitDesc = driver.findElement(By.cssSelector("span[class=\"digit speedLimit\"]"));

            if (!existingLimitDesc.isDisplayed()) {
                WebElement uploadSpeedLimitButton = driver.findElement(By.cssSelector("input[class=\"subBtn eqtBtn noSpeedLimit\"]"));
                uploadSpeedLimitButton.click();
            } else {
                existingLimitDesc.click();
            }

            WebElement uploadSpeedLimitText = driver.findElement(By.cssSelector("input[class=\"speedLimit text\"]"));
            long existingSpeedLimit = Long.parseLong(uploadSpeedLimitText.getAttribute("value"));
            System.out.println("existingSpeedLimit: " + existingSpeedLimit);
            if (targetUploadSpeedLimit != existingSpeedLimit) {
                uploadSpeedLimitText.clear();
                uploadSpeedLimitText.sendKeys(String.valueOf(targetUploadSpeedLimit) + Keys.ENTER);
            }
        } catch (Exception e) {
            setLimitUpload(targetUploadSpeedLimit);
        }

        while (!driver.findElement(By.id("eptMngList")).isDisplayed()) {
            WebElement backToDeviceInfo = driver.findElement(By.id("linkedEpt_rsMenu"));
            backToDeviceInfo.click();
            TimeUnit.SECONDS.sleep(1);
        }
    }

    private void clickManage() {
        try {
            System.out.println("clicking manage button");
            WebElement targetDevice = driver.findElement(By.cssSelector("span[title=\"" + "GlServer" + "\"]"));
            WebElement parent = targetDevice.findElement(By.xpath(".."));
            WebElement targetDeviceButtons = parent.findElement(By.xpath("following-sibling::*[4]"));
            WebElement manageButton = targetDeviceButtons.findElement(By.tagName("input"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", manageButton);
            TimeUnit.MILLISECONDS.sleep(200);
            manageButton.click();
        } catch (Exception e) {
            clickManage();
        }

        System.out.println("manage button clicked");
    }

    private long parseByte(String rawStr, long accBytes) {
        Matcher matcher = speedPattern.matcher(rawStr);
        matcher.find();

        long number = Long.parseLong(matcher.group(1));
        switch (matcher.group(2)) {
            case "KB":
                accBytes += number;
                break;
            case "MB":
                accBytes += number * 1024;
                break;
            case "GB":
                accBytes += number * 1024 * 1024;
                break;
        }

        return accBytes;
    }
}