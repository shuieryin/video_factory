import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NetworkManager {

    private static final String GATEWAY_URL = "http://192.168.1.1/";
    private static final int MAX_UPLOAD_SPEED = 750;
    private static final int DEFAULT_UPLOAD_SPEED = 450;
    private static final int PER_SPEED_UP = 30;

    private WebDriver driver;
    private WebDriverWait wait;
    private Pattern speedPattern = Pattern.compile("(\\d+)(KB|MB|GB)/s");
    private long currentSpeed = DEFAULT_UPLOAD_SPEED;

    NetworkManager() {
        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 20);
        driver.navigate().to(GATEWAY_URL);

        NetworkManager self = this;
        ManageServer.scheduler.scheduleAtFixedRate(
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

    private void reachDeviceInfoPage() throws InterruptedException, IOException {
        if (driver.findElements(By.id("eptMngRCon")).size() < 1) {
            try {
                do {
                    List<WebElement> passwordField;
                    while ((passwordField = driver.findElements(By.id("lgPwd"))).size() < 1) {
                        driver.navigate().to(GATEWAY_URL);
                        CommonUtils.wait(10000, driver);
                    }

                    passwordField.get(0).sendKeys(ManageServer.retrieveData("router_password"));

                    WebElement logonButton = driver.findElement(By.id("loginSub"));
                    CommonUtils.scrollToElement(driver, logonButton);
                    logonButton.click();
                    CommonUtils.wait(1000, driver);
                } while (driver.findElements(By.id("loginError")).size() > 0);

                WebElement deviceManageButton;

                deviceManageButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("routeMgtMbtn")));
                CommonUtils.scrollToElement(driver, deviceManageButton);
                deviceManageButton.click();
            } catch (Exception e) {
                CommonUtils.wait(5000, driver);
                System.out.println("reachDeviceInfoPage error:");
                e.printStackTrace();
                reachDeviceInfoPage();
            }
        }
    }

    void balanceUploadSpeed() throws InterruptedException, IOException {
        System.out.println();
        System.out.println("Start balancing upload speed");
        boolean hasUploadThread = false;
        System.out.println("Checking has existing upload thread");
        for (BilibiliManager bilibiliManager : ManageServer.bilibiliManagersMap.values()) {
            if (null != bilibiliManager.uploadThread && bilibiliManager.uploadThread.isAlive()) {
                hasUploadThread = true;
                break;
            }
        }

        if (!hasUploadThread) {
            System.out.println("No upload thread found, exit blanace upload speed feature");
            return;
        }

        System.out.println("Found upload thread, balancing upload speed");

        reachDeviceInfoPage();

        CommonUtils.scrollToTop(driver);

        WebElement deviceTable = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("eptMngRCon")));
        CommonUtils.scrollToElement(driver, deviceTable);
        Elements deviceInfos;
        do {
            String deviceTableHtmlStr = deviceTable.getAttribute("innerHTML");
            Document deviceTableDom = Jsoup.parse(deviceTableHtmlStr);
            deviceInfos = deviceTableDom.getElementsByClass("vs");
            CommonUtils.wait(1000, driver);
        } while (deviceInfos.isEmpty());

        long totalUploadKiloBytes = 0;
        long totalDownloadKiloBytes = 0;

        for (Element deviceInfo : deviceInfos) {
            String deviceName = deviceInfo.previousSibling().childNode(0).attr("title");
            if ("GlServer".equals(deviceName)) {
                System.out.println("Skip GlServer");
                continue;
            }

            List<Node> childNodes = deviceInfo.childNodes();

            String uploadSpeedStr = childNodes.get(0).toString();
            String downloadSpeedStr = childNodes.get(1).toString();

            long parsedUploadSeed = parseByte(uploadSpeedStr);
            long parsedDownloadSeed = parseByte(downloadSpeedStr);

            totalUploadKiloBytes += parsedUploadSeed;
            totalDownloadKiloBytes += parsedDownloadSeed;
        }

        System.out.println("totalUploadKiloBytes: " + totalUploadKiloBytes);
        System.out.println("totalDownloadKiloBytes: " + totalDownloadKiloBytes);

        clickManage();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("eptMngDetail")));

        if (totalUploadKiloBytes == 0 && totalDownloadKiloBytes == 0) {
            currentSpeed = currentSpeed < MAX_UPLOAD_SPEED ? currentSpeed + PER_SPEED_UP : MAX_UPLOAD_SPEED;
        } else {
            currentSpeed = Math.max(10, DEFAULT_UPLOAD_SPEED - totalUploadKiloBytes);
        }

        System.out.println("targetUploadSpeedLimit: " + currentSpeed);
        setLimitUpload();
    }

    private void setLimitUpload() throws InterruptedException {
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
            if (currentSpeed != existingSpeedLimit) {
                uploadSpeedLimitText.clear();
                uploadSpeedLimitText.sendKeys(String.valueOf(currentSpeed) + Keys.ENTER);
            }
        } catch (Exception e) {
            setLimitUpload();
        }

        while (!driver.findElement(By.id("eptMngList")).isDisplayed()) {
            WebElement backToDeviceInfo = driver.findElement(By.id("linkedEpt_rsMenu"));
            backToDeviceInfo.click();
            CommonUtils.wait(1000, driver);
        }
    }

    private void clickManage() {
        try {
            System.out.println("clicking manage button");
            WebElement targetDevice = driver.findElement(By.cssSelector("span[title=\"" + "GlServer" + "\"]"));
            WebElement parent = targetDevice.findElement(By.xpath(".."));
            WebElement targetDeviceButtons = parent.findElement(By.xpath("following-sibling::*[4]"));
            WebElement manageButton = targetDeviceButtons.findElement(By.tagName("input"));
            CommonUtils.scrollToElement(driver, manageButton);
            manageButton.click();
        } catch (Exception e) {
            clickManage();
        }

        System.out.println("manage button clicked");
    }

    private long parseByte(String rawStr) {
        Matcher matcher = speedPattern.matcher(rawStr);
        matcher.find();

        long number = Long.parseLong(matcher.group(1));
        String speedUnit = matcher.group(2);
        switch (speedUnit) {
            case "MB":
                number *= 1024;
                break;
            case "GB":
                number *= 1024 * 1024;
                break;
        }

        return number;
    }

}
