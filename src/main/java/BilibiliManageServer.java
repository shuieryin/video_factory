import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.xerces.impl.dv.util.Base64;
import org.json.JSONObject;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static Pattern vidPathPattern = Pattern.compile("/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)");
    private ScheduledFuture<?> processVideoScheduler;
    private static final int PER_CLIP_DURATION_SEC = 900;
    private Map<String, ProcessedVideo> processedVideos = new HashMap<>();
    private Runtime rt = Runtime.getRuntime();
    private Socket commandSocket;
    private DataOutputStream commandOut;
    private BufferedReader commandIn;
    private boolean isSystemTurningOff = false;
    private static String eol = System.getProperty("line.separator");
    private static Pattern userInputPattern = Pattern.compile("^([^:]+)[:]?(\\S*)");

    BilibiliManageServer() throws IOException {
        super(4567);

        new Thread(() -> {
            for (; ; ) {
                try {
                    String responseLine = "";
                    while (null != commandIn && null != (responseLine = commandIn.readLine()) && !"done".equals(responseLine)) {
                        System.out.println(responseLine);
                    }

                    if (isSystemTurningOff) {
                        System.out.println("socket control thread closed");
                        break;
                    }

                    if (null == responseLine || null == commandSocket) {
                        commandSocket = new Socket("192.168.1.123", 12345);
                        commandOut = new DataOutputStream(commandSocket.getOutputStream());
                        commandIn = new BufferedReader(new InputStreamReader(commandSocket.getInputStream()));
                        System.out.println("socket connected");
                    }
                } catch (IOException e) {
                    String errorMsg = e.getMessage();
                    if ("Connection refused".equals(errorMsg) || "Connection reset".equals(errorMsg) || "No route to host (Host unreachable)".equals(errorMsg)) {
                        int seconds = 5;
                        System.out.println("reconnecting socket in " + seconds + " second(s)");
                        try {
                            TimeUnit.SECONDS.sleep(seconds);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    } else {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        rt.addShutdownHook(new Thread(() -> {
            try {
                System.out.println("closing...");
                isSystemTurningOff = true;
                if (null != commandOut) {
                    commandOut.writeUTF("close" + eol);
                    commandOut.flush();

                    System.out.println(commandIn.readLine());
                }
                for (BilibiliManager bm : bilibiliManagerMaps.values()) {
                    bm.close();
                }
                executeCommand("kill -9 $(pgrep 'geckodriv|java|firefox')");
                FileUtils.forceDelete(new File(driverPath));
                System.out.println("turned off");
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
            executeCommand("chmod 755 " + driverPath);
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
                    returnJSON.put("status", bm.uploadVideos(processedVideos));
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

        return newFixedLengthResponse(ReturnContent);
    }

    private void executeCommandRemotely(String command) {
        try {
            commandOut.writeUTF(command + eol);
            commandOut.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();

        try {
            System.out.println("executing command: [" + command + "]");
            String[] cmd = {
                    "/bin/bash",
                    "-c",
                    command
            };
            Process proc = rt.exec(cmd);

//            proc.waitFor();

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

            String line;

            // read the output from the command
            while ((line = stdInput.readLine()) != null) {
                output.append(line + "\n");
                System.out.println("cmd in: " + line);
            }

            // read any errors from the attempted command
            while ((line = stdError.readLine()) != null) {
                output.append(line + "\n");
                System.out.println("cmd err: " + line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output.toString();
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
        Matcher inputMatcher = userInputPattern.matcher(userInput);
        inputMatcher.find();

        String command = inputMatcher.group(1);
        String args = inputMatcher.group(2);
        try {
            String testUid = "testUid";
            BilibiliManager bm = bilibiliManagerMaps.get(testUid);
            switch (command) {
                case "ibs":
                    if (null == bm) {
                        bm = new BilibiliManager(testUid);
                        bilibiliManagerMaps.put(testUid, bm);
                    } else {
                        bm.updateExpireTime();
                    }
                    break;
                case "ic":
                    bm.inputCredentials("", "", true);
                    break;
                case "uv":
                    Map<String, ProcessedVideo> pendingUploadVids = new HashMap<>();
                    ProcessedVideo testProcessedVideo = new ProcessedVideo(Calendar.getInstance().getTimeInMillis(), "The Witcher 3", "/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01");
                    testProcessedVideo.setOriginalVideoPath("/Volumes/Anonymous/vids/pending_process/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01.mp4.done.3");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part1.flv");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part2.flv");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part3.flv");
                    pendingUploadVids.put("The Witcher 3 2016.12.22 - 22.06.57.01", testProcessedVideo);
                    System.out.println(bm.uploadVideos(pendingUploadVids));
                    break;
                case "cmd":
                    System.out.println("cmd: " + args);
                    executeCommandRemotely(args + eol);
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
                int vidHour = Integer.parseInt(timerMatcher.group(1));
                int vidMinutes = Integer.parseInt(timerMatcher.group(2));
                int vidSeconds = Integer.parseInt(timerMatcher.group(3));
                long totalSeconds = 1 + vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                vidPathMatcher.find();

                String videoName = vidPathMatcher.group(1);
                String gameName = vidPathMatcher.group(2);
                // String fileExt = vidPathMatcher.group(10);
                String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";
                executeCommand("rm -rf " + processedPath + "; mkdir -p " + processedPath);

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

                long clipCount = Math.floorDiv(totalSeconds, PER_CLIP_DURATION_SEC) + 1;
                for (int i = 0; i < clipCount; i++) {
                    long startPos = i * PER_CLIP_DURATION_SEC;
                    String endTimeStr = i == clipCount - 1 ? "" : "-t " + PER_CLIP_DURATION_SEC + " ";
                    String processedClipPath = processedPath + "part" + (i + 1) + ".flv";
                    processedVideo.addClipPath(processedClipPath);
                    executeCommandRemotely("ffmpeg -i " + parsedVidPath + " -ss " + startPos + " -codec:v libx264 -ar 44100 -crf 15 " + endTimeStr + processedClipPath);
                }

                String processedOriginalVideoPath = parsedVidPath + ".done." + clipCount;
                processedVideo.setOriginalVideoPath(processedOriginalVideoPath);
                executeCommand("mv " + parsedVidPath + " " + processedOriginalVideoPath);

                processedVideos.put(gameName, processedVideo);

                System.out.println(gameName + " total vidSeconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }
}
