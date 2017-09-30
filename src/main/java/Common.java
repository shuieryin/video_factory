public class Common {
    public static String strParse(String src) {
        return src.replaceAll(" ", "\\\\ ").replaceAll("'", "\\\\'").replaceAll("\\(", "\\\\(").replaceAll("\\)", "\\\\)");
    }

    public static String strUnparse(String src) {
        return src.replaceAll("\\\\ ", " ").replaceAll("\\\\'", "'").replaceAll("\\\\\\(", "\\(").replaceAll("\\\\\\)", "\\)");
    }
}
