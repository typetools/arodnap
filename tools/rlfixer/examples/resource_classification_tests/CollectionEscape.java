/* Warning
CollectionEscape.java,13,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.util.ArrayList;

public class CollectionEscape {
	public static void main(String[] args){
		ArrayList<FileWriter> h = new ArrayList<FileWriter>();
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        h.add(a);
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}