/* Warning
ParamEscape.java,18,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.io.BufferedWriter;

public class ParamEscape {
	public static void main(String[] args) throws IOException{
		FileWriter x = new FileWriter("a.txt");
		foo(x);
		x.close();
	}

	public static void foo(FileWriter x){
		try{
			BufferedWriter y = new BufferedWriter(x);
			y.write("Important message 1");
		} catch (Exception e) {
		}
	}
}