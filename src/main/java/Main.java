import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        try {
            ManageServer server = new ManageServer();

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
