/* Warning
EscapesTryCatch.java,12,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class EscapesTryCatch {
	public static void main(String[] args) throws IOException{
		FileWriter a = null;
		try {
	        a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	    a.write("dd");
	    if (a != null){
	    	try{
	    		a.write("aa");
	    	} catch(Exception e){}
	    }
	}
}