
/* Warning
DoubleLoop.java,19,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class DoubleLoop {
	public static void main(String[] args){
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		for (int i = 0 ; i < 2 ; i++){
			for (String j : a){
				try{
					FileWriter myWriter = new FileWriter(j);
					myWriter.write(j);
				} catch (IOException e) {
		        	e.printStackTrace();
		    	}
		    }
		}
		
	}
}