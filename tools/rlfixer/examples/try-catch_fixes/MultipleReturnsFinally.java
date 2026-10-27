
/* Warning
MultipleReturnsFinally.java,13,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class MultipleReturnsFinally {
	public static void main(String[] args){
		FileWriter myWriter = null;
		try {
	        myWriter = new FileWriter("filename.txt");
	        if (false){
	        	System.out.println("True");
	        	myWriter.write("True");
	        } else {
	        	System.out.println("False");
				myWriter.write("False");
				return;
	        }
	        myWriter.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	    } finally{
	    	System.out.println("Finally");
	    }
	    return;
	}
}