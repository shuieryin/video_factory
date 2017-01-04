import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.xerces.impl.dv.util.Base64;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

class Server extends NanoHTTPD {

    private static final String DRIVER_NAME = "geckodriver";
    private String driverPath;
    private Map<String, BilibiliManager> bilibiliManagerMaps = new HashMap<>();

    private Server() throws IOException {
        super(4567);
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        ClassLoader classloader = Thread.currentThread().getContextClassLoader();

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

        System.out.println("\nRunning! Point your browsers to http://localhost:4567/ \n");
    }

    public static void main(String[] args) {
        try {
            Server server = new Server();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("closing...");
                    for (BilibiliManager bm : server.getBilibiliManagerMaps().values()) {
                        bm.close();
                        Runtime.getRuntime().exec(new String[]{"bash", "-c", "kill -9 $(pgrep -i 'geckodriv|java|firefox')"});
                        FileUtils.forceDelete(new File(server.driverPath()));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));

            //noinspection InfiniteLoopStatement
            String ui = "";
            while (!"stop".equalsIgnoreCase(ui)) {
                Scanner userInput = new Scanner(System.in);
                ui = userInput.next();
            }
            System.exit(9);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

    // http://localhost:4567/bilibili_manager?uid=h121234hjk&event=input_credentials&username=shuieryin&password=46127836471823

    @Override
    public Response serve(IHTTPSession session) {
        if (!session.getUri().equalsIgnoreCase("/bilibili_manager")) {
            return newFixedLengthResponse("invalid commands!");
        }

        String ReturnContent = "";

        try {
            Map<String, List<String>> getParams = session.getParameters();
            String uid = getParams.get("uid").get(0);

            BilibiliManager bm = bilibiliManagerMaps.get(uid);

            JSONObject returnJSON = new JSONObject();
            returnJSON.put("uid", uid);
            String event = getParams.get("event").get(0);
            switch (event) {
                case "init_browser_session":
                    if (null == bm) {
                        bm = new BilibiliManager(uid);
                        bilibiliManagerMaps.put(uid, bm);
                    }
                    break;
                case "close_browser_session":
                    bm.close();
                    bilibiliManagerMaps.remove(uid);
                    break;
                case "input_captcha":
                    String inputCaptcha = getParams.get("input_captcha").get(0);
                    boolean isLogonSuccess = bm.tapLogon(inputCaptcha);
                    // returnJSON.put("event", "input_captcha");
                    returnJSON.put("status", isLogonSuccess);
                    break;
                case "input_credentials":
                    String username = getParams.get("username").get(0);
                    String password = getParams.get("password").get(0);
                    if (bm.inputCredentials(username, password)) {
                        returnJSON.put("is_logged_on", true);
                    } else {
                        File captchaImage = bm.captchaImage();
                        System.out.println(captchaImage);

                        InputStream captchaImageIn = new FileInputStream(captchaImage);
                        byte[] captchaImageBytes = IOUtils.toByteArray(captchaImageIn);

                        returnJSON.put("event", "input_captcha");
                        returnJSON.put("is_logged_on", false);
                        returnJSON.put("captcha_image_bytes", Base64.encode(captchaImageBytes));
                    }

                    // get the json array for a string
                    // System.out.println(returnJSON.getJSONArray("genre_title"));
                    // prints ["International","Pop","slow"]

                    // increment a number by 1
                    // returnJSON.increment("genre_id");

                    // quote a string allowing the json to be delivered within html
                    // System.out.println(JSONObject.quote(returnJSON.toString()));
                    break;
            }
            ReturnContent = returnJSON.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // tapLogon();
        // uploadFlow();

        return newFixedLengthResponse(ReturnContent);
    }

    private Map<String, BilibiliManager> getBilibiliManagerMaps() {
        return bilibiliManagerMaps;
    }

    private String driverPath() {
        return driverPath;
    }
}
