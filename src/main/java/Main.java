import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Date startTime = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        sdf.format(startTime);
        try {
            ManageServer server = new ManageServer();

            Scanner scanner = new Scanner(System.in);
            String userInput;
            for (; ; ) {
                userInput = scanner.nextLine();
                if (userInput.isEmpty()) {
                    continue;
                }

                if ("stop".equalsIgnoreCase(userInput)) {
                    Date endTime = new Date();
                    long duration = endTime.getTime() - startTime.getTime();
                    System.out.println("Star time: " + sdf.toLocalizedPattern());
                    sdf.format(endTime);
                    System.out.println("End time: " + sdf.toLocalizedPattern());
                    System.out.println("Duration raw: " + duration);
                    System.out.println("Duration: " + duration / 1000 / 60 / 60);
                    System.exit(0);
                }

                server.handleUserInput(userInput.trim());
            }
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

}
