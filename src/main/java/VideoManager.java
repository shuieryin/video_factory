import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class VideoManager {

    private static final String OUTPUT_FORMAT = "flv";
    @SuppressWarnings("FieldCanBeLocal")
    private static int OVERLAP_DURATION_SECONDS = 1;
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static final String vidPathPatternStr = "^(.*)/(([^/]+)\\s(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    //    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20);
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final long PART_PER_SECONDS = 1800;
    private static final String SOURCE_PATH = "/run/user/1000/gvfs/smb-share:server=192.168.1.111,share=smbshare/vids/pending_merge";
    private static final String PENDING_MERGE_PATH = "/srv/grand_backup/samba/vids/pending_merge";
    private static final String MERGED_PATH = "/srv/grand_backup/samba/vids/merged";
    private static final String PENDING_PROCESS_PATH = "/srv/grand_backup/samba/vids/pending_process";

    private static final int HEIGHT_SIZE = 1280;
    private static final int WIDTH_SIZE = 720;
    private static final int TWO_PASS_BIT_RATE = 1760;
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_BIT_RATE = 192;

    private static final String TWO_PASS_ENCODE_PARAMS = " " +
            "  -threads 0 " +
            "  -b:v " + TWO_PASS_BIT_RATE + "k " +
            "  -maxrate " + TWO_PASS_BIT_RATE + "k " +
            "  -bufsize 10M " +
            "  -acodec aac -ar " + SAMPLE_RATE + " -b:a " + AUDIO_BIT_RATE + "k " +
            "  -vcodec libx264 " +
            "  -x264opts " +
            "threads=0:" +
            "8x8dct=1:" +
            "partitions=all:" +
            "subme=10:" +
            "b-adapt=2:" +
            "deblock=0,0:" +
            "ipratio=1.41:" +
            "direct=auto:" +
            "chroma-qp-offset=1:" +
            "colormatrix=bt709:" +
            "me=esa:" +
            "merange=24:" +
            "mixed-refs=1:" +
            "psy-rd=0.5,0.0:" +
            "qcomp=0.7:" +
            "trellis=2:" +
            "weightb=1:" +
            "aq-mode=2:" +
            "level=4.1 " +
            "  -b_strategy 2 " +
            "  -chromaoffset 1 " +
            "  -tune film " +
            "  -partitions all " +
            "  -subq 10 " +
            "  -me_method full " +
            "  -me_range 24 " +
            "  -i_qfactor 1.41 " +
            "  -trellis 2 " +
            "  -mbd rd " +
            "  -color_primaries bt709 " +
            "  -color_trc bt709 " +
            "  -colorspace bt709 " +
            "  -color_range jpeg " +
            "  -bidir_refine 4 " +
            "  -aq-mode 2 " +
            "  -cmp chroma " +
            "  -preme 1 " +
            "  -precmp rd " +
            "  -vf " +
            "scale=" +
            "w=" + HEIGHT_SIZE + ":" +
            "h=" + WIDTH_SIZE + " ";
//            "unsharp=" +
//            "luma_msize_x=5:" +
//            "luma_msize_y=5:" +
//            "luma_amount=1.5 ";

    private Map<String, ProcessedGame> processedGames = new LinkedHashMap<>();

    VideoManager() throws IOException {
        ManageServer.executeCommand("cp " + SOURCE_PATH + " " + PENDING_MERGE_PATH);
        initProcessVideo();
    }

    Map<String, ProcessedGame> mergeVideos() {
        try {
            processedGames = new LinkedHashMap<>();
            System.out.println("=======merging videos...");
            List<String> pendingMergePaths = pendingProcessVids(PENDING_MERGE_PATH, false);
            if (pendingMergePaths.isEmpty()) {
                System.out.println("=======no videos to merge...");
                return processedGames;
            }

            for (String vidPath : pendingMergePaths) {
                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("=======vidPath not found: " + vidPath);
                    continue;
                }
                String gameName = vidPathMatcher.group(3);
                ProcessedGame processedGame = processedGames.get(gameName);
                if (null == processedGame) {
                    processedGame = new ProcessedGame(gameName);
                    processedGames.put(gameName, processedGame);
                }
                processedGame.addPendingMergeVidPath(vidPath);
            }

            for (Map.Entry<String, ProcessedGame> entry : processedGames.entrySet()) {
                ProcessedGame processedGame = entry.getValue();
                List<String> vidsPath = processedGame.pendingMergeVidsInfos();
                if (vidsPath.isEmpty()) {
                    continue;
                }

                String processFilePath, pendingMergeFolder, parsedProcessFilePath = "", pendingProcessFolder = "", firstVidPath = "";
                for (int i = 0; i < vidsPath.size(); i++) {
                    String oriPath = vidsPath.get(i);
                    String vidPath = Common.strParse(oriPath);
                    System.out.println("=======vidPath: " + vidPath);
                    if (i == 0) {
                        firstVidPath = vidPath;
                        Matcher vidPathMatcher = vidPathPattern.matcher(oriPath);
                        if (!vidPathMatcher.find()) {
                            System.out.println("=======vidPath not found: " + oriPath);
                            continue;
                        }
                        pendingMergeFolder = Common.strParse(vidPathMatcher.group(1));
                        processedGame.setPendingMergeFolder(pendingMergeFolder);
                        System.out.println("=======pendingMergeFolder: " + pendingMergeFolder);

                        String gameName = Common.strParse(vidPathMatcher.group(3));

                        pendingProcessFolder = PENDING_PROCESS_PATH + "/" + gameName;
                        processedGame.setPendingProcessFolder(pendingProcessFolder);
                        System.out.println("=======pendingProcessFolder: " + pendingProcessFolder);

                        processFilePath = pendingProcessFolder + "/" + gameName + ".txt";
                        parsedProcessFilePath = processFilePath;
                        System.out.println("=======parsedProcessFilePath: " + parsedProcessFilePath);
                        ManageServer.executeCommand("rm -rf " + pendingProcessFolder);
                        ManageServer.executeCommand("mkdir -p " + pendingProcessFolder);
                    }

                    ManageServer.executeCommand("echo \"file " + vidPath + "\" | tee -a " + parsedProcessFilePath);
                    ManageServer.executeCommand("echo $'\\r' >> " + parsedProcessFilePath);
                }
                if (vidsPath.size() == 1) {
                    ManageServer.executeCommand("cp " + firstVidPath + " " + pendingProcessFolder);
                } else {
                    String finalOutputName = firstVidPath.replaceFirst("pending_merge", "pending_process");
                    String concatVidsCommand = "ffmpeg -f concat -safe 0 -i " + parsedProcessFilePath + " -vcodec copy -acodec copy " + finalOutputName;
                    ManageServer.executeCommandRemotely(concatVidsCommand, true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            processedGames = new LinkedHashMap<>();
        }

        return processedGames;
    }

    void processVideos() {
        System.out.println();
        System.out.println("=======processing videos...");

        try {
            List<String> pendingProcessPaths = pendingProcessVids(PENDING_PROCESS_PATH, false);
            System.out.println(pendingProcessPaths);
            for (String vidPath : pendingProcessPaths) {
                System.out.println("=======vidPath: " + vidPath);
                String parsedVidPath = Common.strParse(vidPath);
                long totalDuration = videoDuration(parsedVidPath);
                if (0 == totalDuration) {
                    continue;
                }
                System.out.println("=======totalDuration: " + totalDuration);

                long clipCount = 0;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("=======vidPath not found: " + vidPath);
                    continue;
                }
                //String pending_merge_folder = pending_process_folder.replaceFirst("pending_process", "pending_merge");
                String gameName = vidPathMatcher.group(3);
                System.out.println("=======gameName: " + gameName);

                ProcessedGame processedGame = processedGames.get(gameName);
                if (null == processedGame) {
                    processedGame = new ProcessedGame(gameName);
                    processedGames.put(gameName, processedGame);
                }

                Map<String, ProcessedVideo> processedVideos = processedGame.processedVideos();
                ProcessedVideo processedVideo = processedVideos.get(vidPath);
                if (null == processedVideo) {
                    processedVideo = new ProcessedVideo(vidPath, vidPathPattern);
                    processedVideos.put(vidPath, processedVideo);
                    processedGame.addProcessedVideo(vidPath, processedVideo);
                }

                String twoPassedFilePath = PENDING_PROCESS_PATH + "/" + Common.strParse(gameName) + "/temp.mp4";
                String firstPassCommand = "ffmpeg -y -i " +
                        parsedVidPath +
                        TWO_PASS_ENCODE_PARAMS +
                        " -pass 1 -f mp4 /dev/null";
                ManageServer.executeCommandRemotely(firstPassCommand, true);
                String secondPassCommand = "ffmpeg -y -i " +
                        parsedVidPath +
                        TWO_PASS_ENCODE_PARAMS +
                        " -pass 2 " +
                        twoPassedFilePath;
                ManageServer.executeCommandRemotely(secondPassCommand, true);

                long startPos = 0, lastStartPos = 0;
                String lastProcessedClipPath;
                do {
                    lastProcessedClipPath = processedVideo.processedPath + processedVideo.uuid() + "-" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String thirdPassCommand = "ffmpeg -y -i " +
                            twoPassedFilePath +
                            " -ss " + lastStartPos +
                            " -t " + PART_PER_SECONDS + " " +
                            " -vcodec copy " +
                            lastProcessedClipPath;

                    ManageServer.executeCommandRemotely(thirdPassCommand, true);

                    long lastClipDuration = videoDuration(lastProcessedClipPath);
                    long remainDuration = totalDuration - startPos - lastClipDuration;

                    lastStartPos += lastClipDuration - OVERLAP_DURATION_SECONDS;
                    startPos += lastClipDuration - OVERLAP_DURATION_SECONDS;

                    System.out.println();
                    System.out.println("=========lastClipDuration: " + lastClipDuration);
                    System.out.println("=========ClipStartPos: " + startPos);
                    if (remainDuration > 0) {
                        System.out.println("=========target clipCount: " + (clipCount + 1));
                    }
                    System.out.println("=========remainDuration: " + remainDuration);
                    System.out.println("=========totalDuration: " + totalDuration);
                    System.out.println();
                } while (startPos < totalDuration - 2);

                String mergedPath = MERGED_PATH + "/" + Common.strParse(gameName);
                StringBuilder removePendingMergeVidsInfosBuilder = new StringBuilder();
                removePendingMergeVidsInfosBuilder.append("mkdir -p ");
                removePendingMergeVidsInfosBuilder.append(mergedPath);
                removePendingMergeVidsInfosBuilder.append("; ");
                for (String pendingMergeVidPath : processedGame.pendingMergeVidsInfos()) {
                    removePendingMergeVidsInfosBuilder.append("mv ");
                    removePendingMergeVidsInfosBuilder.append(Common.strParse(pendingMergeVidPath));
                    removePendingMergeVidsInfosBuilder.append(" ");
                    removePendingMergeVidsInfosBuilder.append(mergedPath);
                    removePendingMergeVidsInfosBuilder.append("/; ");
                }

                removePendingMergeVidsInfosBuilder.append("rm -rf ");
                removePendingMergeVidsInfosBuilder.append(processedGame.pendingProcessFolder());
                ManageServer.executeCommand(removePendingMergeVidsInfosBuilder.toString());

                System.out.println("=======" + processedVideo.gameName() + " total vidDuration: " + totalDuration + ", and chopped into " + clipCount + " part(s).");

                processedGames.remove(processedGame.gameName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("=======process videos done");
        System.out.println();
    }

    private List<String> pendingProcessVids(String path, boolean isGetDones) throws IOException {
        List<String> vidsList = new ArrayList<>();

        Path rootPath = Paths.get(path);

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

        int result = vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;
        return result <= 0 ? 0 : result + 1;
    }

    private void initProcessVideo() throws IOException {
        System.out.println();
        System.out.println("initProcessVideo start");
        List<String> pendingUploadVids = pendingProcessVids(PENDING_MERGE_PATH, true);

        for (String pendingUploadVid : pendingUploadVids) {
            System.out.println("init pending upload vid: " + pendingUploadVid);
            Matcher vidPathMatcher = processedVidPathPattern.matcher(pendingUploadVid);
            if (!vidPathMatcher.find()) {
                continue;
            }

            String gameName = vidPathMatcher.group(3);
            ProcessedGame processedGame = processedGames.get(gameName);
            if (null == processedGame) {
                processedGame = new ProcessedGame(gameName);
                processedGames.put(gameName, processedGame);
            }

            ProcessedVideo processedVideo = new ProcessedVideo(pendingUploadVid, processedVidPathPattern);
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
                case "pending_process_vids":
                    returnJSON.put("vids_list", pendingProcessVids(PENDING_PROCESS_PATH, false));
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids(PENDING_PROCESS_PATH, true));
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
}