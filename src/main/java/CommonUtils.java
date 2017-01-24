import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.concurrent.TimeUnit;

class CommonUtils {

    static void scrollToElement(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
        wait(500, driver);
    }

    static void wait(long millisecond, WebDriver driver) {
        driver.manage().timeouts().implicitlyWait(millisecond, TimeUnit.MILLISECONDS);
    }

    static void scrollToTop(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        wait(500, driver);
    }

    @SuppressWarnings({"WeakerAccess", "unused"})
    static void inifiniteClick(WebElement element, WebDriver driver) {
        try {
            element.click();
        } catch (Exception e) {
            System.out.println("inifiniteClick failed");
            e.printStackTrace();
            wait(1000, driver);
            inifiniteClick(element, driver);
        }
    }

}
