/* Warning
MapEscape.java,14,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.util.HashMap;

public class MapEscape {
	static HashMap<Integer,FileWriter> h;
	public static void main(String[] args){
		h = new HashMap<Integer,FileWriter>();
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        h.put(0,a);
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}