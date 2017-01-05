import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.xerces.impl.dv.util.Base64;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class BilibiliManageServer extends NanoHTTPD {

    private static String OS = System.getProperty("os.name").toLowerCase();
    private static final String DRIVER_NAME = "geckodriver";
    private String driverPath;
    private Map<String, BilibiliManager> bilibiliManagerMaps = new HashMap<>();
    private Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private Pattern vidPathPattern = Pattern.compile("/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.([a-zA-Z0-9]+))");
    private ScheduledFuture<?> processVideoScheduler;
    private static final int PER_CLIP_DURATION_SEC = 900;

    BilibiliManageServer() throws IOException {
        super(4567);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("closing...");
                for (BilibiliManager bm : bilibiliManagerMaps.values()) {
                    bm.close();
                }
                Runtime.getRuntime().exec(new String[]{"bash", "-c", "kill -9 $(pgrep 'geckodriv|java|firefox')"});
                FileUtils.forceDelete(new File(driverPath));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(
                () -> {
                    if (null == processVideoScheduler || processVideoScheduler.isDone()) {
                        processVideoScheduler = scheduler.schedule(
                                this::processVideos,
                                10,
                                TimeUnit.SECONDS
                        );
                    }

                    long now = Calendar.getInstance().getTimeInMillis();
                    for (BilibiliManager bm : bilibiliManagerMaps.values()) {
                        if (bm.expireTime() < now) {
                            bm.close();
                            bilibiliManagerMaps.remove(bm.uid());
                        }
                    }
                },
                10,
                60 * 5,
                TimeUnit.SECONDS
        );

        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        ClassLoader classloader = Thread.currentThread().getContextClassLoader();

        if (OS.contains("win")) {
            driverPath = DRIVER_NAME + ".exe";
        } else if (OS.contains("mac")) {
            driverPath = "mac" + DRIVER_NAME;
        } else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
            driverPath = "linux" + DRIVER_NAME;
        } else {
            throw (new RuntimeException("Your OS is not supported!! [" + OS + "]"));
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

    // http://localhost:4567/bilibili_manager?uid=h121234hjk&event=input_credentials&username=shuieryin&password=46127836471823

    @Override
    public Response serve(IHTTPSession session) {
        // Print request info - START
        Map<String, List<String>> getParams = session.getParameters();
        String requestMainInfo = "[" + session.getUri() + "], event [" + getParams.get("event").get(0) + "]";
        System.out.println();
        System.out.println("Receiving request: " + requestMainInfo);
        System.out.println("Get params:");
        getParams.forEach((key, value) -> System.out.println(key + "=" + value.get(0)));

        System.out.println();
        // Print request info - END

        if (!session.getUri().equalsIgnoreCase("/bilibili_manager")) {
            return newFixedLengthResponse("invalid commands!");
        }

        String ReturnContent = "";

        try {
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
                    } else {
                        bm.updateExpireTime();
                    }
                    break;
                case "close_browser_session":
                    bm.close();
                    bilibiliManagerMaps.remove(uid);
                    break;
                case "input_captcha":
                    String inputCaptcha = getParams.get("input_captcha").get(0);
                    boolean isLogonSuccess = bm.tapLogon(inputCaptcha);
                    returnJSON.put("status", isLogonSuccess);
                    break;
                case "input_credentials":
                    String username = getParams.get("username").get(0);
                    String password = getParams.get("password").get(0);
                    boolean isReopenUrl = "true".equals(getParams.get("is_reopen_url").get(0));
                    if (bm.inputCredentials(username, password, isReopenUrl)) {
                        returnJSON.put("is_logged_on", true);
                    } else {
                        latestCaptcha(bm, returnJSON);
                    }
                    break;
                case "pending_process_vids":
                    returnJSON.put("vids_list", pendingProcessVids(false));
                    break;
                case "get_latest_captcha":
                    latestCaptcha(bm, returnJSON);
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids(true));
                    break;
                case "upload_vids":
                    returnJSON.put("is_logged_on", false);
                    break;
            }

            // Print response info - START
            System.out.println();
            System.out.println("Response payload: " + requestMainInfo);
            returnJSON.toMap().forEach((key, value) -> System.out.println(key + "=" + value));
            System.out.println();
            // Print response info - END

            ReturnContent = returnJSON.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // tapLogon();
        // uploadFlow();

        return newFixedLengthResponse(ReturnContent);
    }

    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();

        Process p;
        try {
            System.out.println("executing command: [" + command + "]");
            String[] cmd = {
                    "/bin/sh",
                    "-c",
                    command
            };
            p = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line + "\n");
                System.out.println("bash: " + line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output.toString();
    }

    private List<String> pendingProcessVids(boolean isGetDones) throws IOException {
        List<String> vidsList = new ArrayList<>();

        Path rootPath = Paths.get("/root/vids/pending_process");
        Stream<Path> gamePaths = Files.walk(rootPath);
        gamePaths.forEach(gamePath -> {
            if (Files.isDirectory(gamePath) && !gamePath.toString().equals(rootPath.toString())) {
                try (Stream<Path> vidsPath = Files.walk(gamePath)) {
                    vidsPath.forEach(vidPath -> {
                        if (Files.isDirectory(vidPath)) {
                            return;
                        }

                        String vidPathStr = vidPath.toString();
                        Matcher isDone = processedVidPattern.matcher(vidPathStr);
                        boolean isMatch = isDone.find();
                        if (isGetDones && isMatch || !isGetDones && !isMatch) {
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

    private void latestCaptcha(BilibiliManager bm, JSONObject returnJSON) throws IOException {
        File captchaImage = bm.captchaImage();
        System.out.println(captchaImage);

        InputStream captchaImageIn = new FileInputStream(captchaImage);
        byte[] captchaImageBytes = IOUtils.toByteArray(captchaImageIn);

        returnJSON.put("event", "input_captcha");
        returnJSON.put("is_logged_on", false);
        returnJSON.put("captcha_image_bytes", Base64.encode(captchaImageBytes));
    }

    void handleUserInput(String userInput) {
        try {
            switch (userInput) {
                case "gd":
                    pendingProcessVids(true).forEach(System.out::println);
                    break;
                case "ngd":
                    pendingProcessVids(false).forEach(System.out::println);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean processVideos() {
        try {
            String replaceSpace = "\\s";
            pendingProcessVids(false).forEach(vidPath -> {
                String parsedVidPath = vidPath.replaceAll(replaceSpace, "\\\\ ");
                String timeOutput = executeCommand("ffmpeg -i " + parsedVidPath + " 2>&1 | grep Duration | grep -oP \"^\\s*Duration:\\s*\\K(\\S+),\" | cut -c 1-11");

                Matcher timerMatcher = timePattern.matcher(timeOutput);
                timerMatcher.find();
                int hour = Integer.parseInt(timerMatcher.group(1));
                int minutes = Integer.parseInt(timerMatcher.group(2));
                int seconds = Integer.parseInt(timerMatcher.group(3));
                long totalSeconds = 1 + seconds + 60 * minutes + 60 * 60 * hour;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                vidPathMatcher.find();

                String videoName = vidPathMatcher.group(1);
                String gameName = vidPathMatcher.group(2);
                String fileExt = vidPathMatcher.group(10);
                String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";
                executeCommand("rm -rf " + processedPath + "; mkdir -p " + processedPath);

                long clipCount = Math.floorDiv(totalSeconds, PER_CLIP_DURATION_SEC) + 1;
                StringBuilder splitCommand = new StringBuilder("ffmpeg -i " + parsedVidPath);
                for (int i = 0; i < clipCount; i++) {
                    long startPos = i * PER_CLIP_DURATION_SEC;
                    String endTimeStr = i == clipCount - 1 ? "" : "-t " + PER_CLIP_DURATION_SEC + " ";
                    splitCommand.append(" -ss " + startPos + " -codec copy " + endTimeStr + processedPath + "part" + (i + 1) + "." + fileExt);
                }

                executeCommand(splitCommand.toString());

                executeCommand("mv " + parsedVidPath + " " + parsedVidPath + ".done." + clipCount);
                System.out.println(gameName + " total seconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }
}
