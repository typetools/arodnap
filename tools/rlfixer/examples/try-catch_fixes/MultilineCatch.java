/* Warning
MultilineCatch.java,11,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class MultilineCatch {
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        a.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	        System.out.println("aa");
	        return;
	    }
	}
}