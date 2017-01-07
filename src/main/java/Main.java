import java.io.IOException;
import java.util.Scanner;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) {
        try {
            BilibiliManageServer server = new BilibiliManageServer();

            Scanner scanner = new Scanner(System.in);
            String userInput;
            while (!"stop".equalsIgnoreCase(userInput = scanner.next())) {
                server.handleUserInput(userInput);
            }
            System.exit(9);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

}
