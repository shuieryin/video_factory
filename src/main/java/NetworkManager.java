import org.apache.commons.lang3.StringUtils;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NetworkManager {

    private static final String GATEWAY_URL = "http://192.168.1.1/";
    private static int maxUploadSpeed = 750;
    private static int defaultUploadSpeed;
    private static final int PER_SPEED_UP = 20;

    private WebDriver driver;
    private WebDriverWait wait;
    private Pattern speedPattern = Pattern.compile("(\\d+)(KB|MB|GB)/s");
    private long currentSpeed = defaultUploadSpeed;

    NetworkManager() {
        initSpeed();

        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 60);

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
                150,
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
                        CommonUtils.wait(40000, driver);
                    }

                    passwordField.get(0).sendKeys(ManageServer.retrieveData("router_password"));
                    CommonUtils.wait(2000, driver);

                    WebElement logonButton = driver.findElement(By.id("loginSub"));
                    CommonUtils.scrollToElement(driver, logonButton);
                    logonButton.click();
                } while (driver.findElements(By.id("loginError")).size() > 0);

                WebElement deviceManageButton;

                deviceManageButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("routeMgtMbtn")));
                CommonUtils.scrollToElement(driver, deviceManageButton);
                deviceManageButton.click();
            } catch (Exception e) {
                CommonUtils.wait(40000, driver);
                System.out.println("reachDeviceInfoPage error:");
                e.printStackTrace();
                reachDeviceInfoPage();
            }
        }
    }

    void limit(String args) {
        if (args.isEmpty()) {
            System.out.println("Limit args empty.");
            return;
        }

        String[] argsArr = args.split(StringUtils.SPACE);
        int index = 0;
        for (String arg : argsArr) {
            if (arg.equals("-u")) {
                int targetIndex = index + 1;
                if (targetIndex > args.length() - 1) {
                    System.out.println("Invalid upload speed args.");
                    break;
                }

                String uploadSpeedStr = argsArr[targetIndex];
                if (!StringUtils.isNumeric(uploadSpeedStr)) {
                    System.out.println("Invalid upload speed args.");
                    break;
                }

                int uploadSpeed = Integer.parseInt(uploadSpeedStr);
                System.out.println("Set max upload speed from [" + maxUploadSpeed + "] to [" + uploadSpeed + "] done.");
                maxUploadSpeed = uploadSpeed;
            }

            index++;
        }

        initSpeed();
    }

    private void balanceUploadSpeed() throws InterruptedException, IOException {
        System.out.println();
        boolean hasUploadThread = false;
        for (BilibiliManager bilibiliManager : ManageServer.bilibiliManagersMap.values()) {
            if (null != bilibiliManager.uploadThread && bilibiliManager.uploadThread.isAlive()) {
                hasUploadThread = true;
                break;
            }
        }

        if (!hasUploadThread) {
            System.out.println("[" + LocalDateTime.now() + "] No upload thread found, exit blanace upload speed feature");
            return;
        }

        reachDeviceInfoPage();

        CommonUtils.scrollToTop(driver);

        List<WebElement> deviceTables;
        while ((deviceTables = driver.findElements(By.id("eptMngRCon"))).size() < 1) {
            CommonUtils.wait(5000, driver);
        }
        WebElement deviceTable = deviceTables.get(0);
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

        clickManage();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("eptMngDetail")));

        if (totalUploadKiloBytes == 0 && totalDownloadKiloBytes == 0) {
            currentSpeed = currentSpeed < maxUploadSpeed ? currentSpeed + PER_SPEED_UP : maxUploadSpeed;
        } else {
            currentSpeed = Math.max(10, defaultUploadSpeed - totalUploadKiloBytes);
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
            WebElement targetDevice = driver.findElement(By.cssSelector("span[title=\"" + "GlServer" + "\"]"));
            WebElement parent = targetDevice.findElement(By.xpath(".."));
            WebElement targetDeviceButtons = parent.findElement(By.xpath("following-sibling::*[4]"));
            WebElement manageButton = targetDeviceButtons.findElement(By.tagName("input"));
            CommonUtils.scrollToElement(driver, manageButton);
            manageButton.click();
        } catch (Exception e) {
            clickManage();
        }
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

    private void initSpeed() {
        defaultUploadSpeed = maxUploadSpeed - 250;
        if (defaultUploadSpeed < 0) {
            defaultUploadSpeed = PER_SPEED_UP;
        }
    }

}
