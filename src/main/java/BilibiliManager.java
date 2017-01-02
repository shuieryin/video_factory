import org.apache.commons.io.FileUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.Point;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
//import spark.Spark;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class BilibiliManager {

    private static final String DRIVER_NAME = "geckodriver";
    private static final String LOGON_URL = "https://passport.bilibili.com/login";
    private static final String CAPTCHA_IMG_PATH = "captchaImg.png";
    private static final String UPLOAD_URL = "http://member.bilibili.com/v/video/submit.html";

    private String uid;
    private String driverPath;
    private WebDriver driver;
    private WebDriverWait wait;

    BilibiliManager(String Uid) {
        this.uid = Uid;
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();

        System.out.println("launching firefox browser");

        if (OSValidator.isWindows()) {
            driverPath = DRIVER_NAME + ".exe";
        } else if (OSValidator.isMac()) {
            driverPath = "mac" + DRIVER_NAME;
        } else if (OSValidator.isUnix()) {
            driverPath = "linux" + DRIVER_NAME;
        } else {
            throw (new RuntimeException("Your OS is not support!!"));
        }

        InputStream driverStream = classloader.getResourceAsStream(driverPath);

        try {
            Files.copy(driverStream, Paths.get(driverPath), StandardCopyOption.REPLACE_EXISTING);
            Runtime.getRuntime().exec(new String[]{"bash", "-c", "chmod 755 " + driverPath});
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.setProperty("webdriver.gecko.driver", driverPath);
        driver = new FirefoxDriver();
        wait = new WebDriverWait(driver, 10);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                closeDriver();
                FileUtils.forceDelete(new File(driverPath));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
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

    void tapLogon() {
        WebElement headDOM = driver.findElement(By.className("head_foot_content"));
        WebElement logonForm = headDOM.findElement(By.tagName("form"));
        logonForm.submit();

        //driver.manage().timeouts().implicitlyWait(1000L, TimeUnit.MICROSECONDS);
    }

    void inputCredentials(String username, String password) throws IOException, InterruptedException, AWTException {
        driver.navigate().to(LOGON_URL);

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("$(\"#captchaImg\").attr(\"src\", \"/captcha\");$('#captchaImg').show();");

        WebElement vdCodeField = driver.findElement(By.id("vdCodeTxt"));
        vdCodeField.clear();

        WebElement captchaImg = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("captchaImg")));
        //driver.manage().timeouts().implicitlyWait(500L, TimeUnit.MICROSECONDS);

        WebElement usernameField = driver.findElement(By.name("userid"));
        usernameField.clear();
        usernameField.sendKeys(username);

        WebElement passwordField = driver.findElement(By.name("pwd"));
        passwordField.clear();
        passwordField.sendKeys(password);

        // Capture captcha image - START
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
        // Capture captcha image - END
    }

    private void closeDriver() throws IOException {
        if (driver != null) {
            driver.close();
            Runtime.getRuntime().exec(new String[]{"bash", "-c", "kill -9 $(pgrep -i 'geckodriv|java')"}); //|firefox
        }
    }

    @SuppressWarnings("unused")
    public String getUid() {
        return uid;
    }
}