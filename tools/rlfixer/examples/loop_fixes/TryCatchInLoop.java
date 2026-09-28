
/* Warning
TryCatchInLoop.java,18,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class TryCatchInLoop {
	public static void main(String[] args){
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		for (String i : a){
			try{
				FileWriter myWriter = new FileWriter(i);
				myWriter.write("foo");
			} catch (IOException e) {
	        	e.printStackTrace();
	    	}
		}
	}
}