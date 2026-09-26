/* Warning
MultipleCatch.java,11,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class MultipleCatch {
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        a.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	    } catch (Exception f){
	    	f.printStackTrace();
	    }
	}
}