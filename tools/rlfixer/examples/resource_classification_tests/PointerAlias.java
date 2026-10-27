/* 
PointerAlias.java,11,null#PointerAlias.java,13,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.io.Closeable;
public class PointerAlias {
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        if (a==null){
	        	a = new FileWriter("g.txt");;
	        }
	        a.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}