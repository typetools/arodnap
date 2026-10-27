
/* Warning
ResourceEscapeDoubleLoop.java,20,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ResourceEscapeDoubleLoop {
	public static void main(String[] args) throws IOException{
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		for (int j = 0 ; j < 2; j++){
			FileWriter myWriter = null;
			for (String i : a){
				try{
					myWriter = new FileWriter(i);
					myWriter.write(i);
				} catch (IOException e) {
		        	e.printStackTrace();
		    	}
			}
			myWriter.write("a");
		}
		
	}
}