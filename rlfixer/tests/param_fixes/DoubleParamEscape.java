/* Warning
DoubleParamEscape.java,22,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.io.BufferedWriter;

public class DoubleParamEscape {
	public static void main(String[] args) throws IOException{
		FileWriter x = new FileWriter("a.txt");
		foo2(x);
		x.close();
	}

	public static void foo2(FileWriter x){
		foo(x);
	}

	public static void foo(FileWriter x){
		try{
			BufferedWriter y = new BufferedWriter(x);
			y.write("Important message 1");
		} catch (Exception e) {
		}
	}
}