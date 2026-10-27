
/* Warning
LoopInTryCatch.java,18,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class LoopInTryCatch {
	public static void main(String[] args){
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		try{
			for (String i : a){
				FileWriter myWriter = new FileWriter(i);
				myWriter.write("foo");	
			}
		} catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}