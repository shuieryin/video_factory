import org.apache.commons.io.FileUtils;
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
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

class BilibiliManager {

    private static final String LOGON_URL = "https://passport.bilibili.com/login";
    private static final String CAPTCHA_IMG_PATH = "captchaImg.png";
    private static final String UPLOAD_URL = "http://member.bilibili.com/v/video/submit.html";
    private static long expireTime;

    private String uid;
    private WebDriver driver;
    private WebDriverWait wait;

    BilibiliManager(String Uid) {
        this.uid = Uid;

        System.out.println("launching firefox browser");

        updateExpireTime();

        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 10);
    }

    void uploadFlow() throws IOException, InterruptedException, AWTException {
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("b_live")));

        driver.navigate().to(UPLOAD_URL);

        WebElement hintDOM = wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("home-hint")));
        System.out.println(hintDOM.getAttribute("innerHTML"));
//        WebElement postBox = driver.findElement(By.className("u-i b-post"));
//        WebElement contributeButton = postBox.findElement(By.className("i-link"));
//        contributeButton.click();
    }

    boolean tapLogon(String inputCaptcha) {
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

    String uid() {
        return uid;
    }
}