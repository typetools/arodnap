
/* Warning
EscapedTryCatchInLoop.java,19,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class EscapedTryCatchInLoop {
	public static void main(String[] args) throws IOException{
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		for (String i : a){
			FileWriter myWriter = null;
			try{
				myWriter = new FileWriter(i);
				myWriter.write("foo");
			} catch (IOException e) {
	        	e.printStackTrace();
	    	}
	    	myWriter.write("ss");
		}
	}
}