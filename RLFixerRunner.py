'''
Front-end for the Resource-Leak fixing post-processor

'''
import os
import argparse


class Warning:
    def __init__(self, f, l, m, o = False):
        self.filename = f
        self.line_number = l
        self.method = m
        self.is_owning_overwrite = o

    def equals(self, other):
        # Option 1: filename match and lines match by +-2
        if self.filename == other.filename:
            if abs(self.line_number - other.line_number) <= 2:
                return True
        # Option 2: method name match
        if self.method!=None and other.method!=None:
            if self.method == other.method:
                return True
        return False

    def __str__(self):
        return self.filename + "#" + str(self.line_number) + "#" + str(self.method) + "#" + str(self.is_owning_overwrite)


def get_checkerframework_warnings(result_file, benchmark_folder):
    warnings = []
    with open(result_file) as fp:
        lines = [e.strip() for e in fp.readlines()]
    try:
        i = 0
        for line in lines:
            if "(required.method.not.called)" in line:
                filename = line.split(":")[0].replace(benchmark_folder,"")
                line_number = int(line.split(":")[1])
                is_owning_overwrite = False
                if i + 4 < len(lines):
                    if "Non-final owning field might be overwritten" in lines[i + 4]:
                        is_owning_overwrite = True
                warnings.append(Warning(filename,line_number,None,is_owning_overwrite))
            i += 1
    except Exception as e:
        print('Exception', e)
        
    return warnings



# Parse arguments
p = argparse.ArgumentParser()
p.add_argument("--tool", help="Toolname whose results we are post-processing (infer, codeguru, pmd, checkerframework, spotbugs)")
p.add_argument("--results", help="Location of the tool's results folder")
p.add_argument("--benchmarks", help="Location with the NJR benchmarks")
p.add_argument("--output", help="Location to place output files")
p.add_argument("--skip", help="List of benchmarks to skip")
p.add_argument("--debug_output", help="Location to place debug files")
p.add_argument("--wpioutdir", help="Location of the wpi-out directory from running the CF RLC inference")
args = p.parse_args()
TOOL = args.tool
RESULT_LOCATION = args.results
BENCHMARKS_FOLDER = args.benchmarks
OUTPUT_FOLDER = args.output
WPI_OUT_DIR = args.wpioutdir

# String Constants
CHECKERFRAMEWORK = "checkerframework"
HERE = os.path.dirname(os.path.abspath(__file__))
RLFIXER_ROOT = os.path.abspath(os.path.join(HERE, "rlfixer"))

COMPILED_FOLDER = f"{RLFIXER_ROOT}/wala/classes/"
DRIVER_CLASS = "main.Main"
WALA_CORE_JAR = f"{RLFIXER_ROOT}/lib/com.ibm.wala.core-1.5.7.jar"
WALA_SHRIKE_JAR = f"{RLFIXER_ROOT}/lib/com.ibm.wala.shrike-1.5.7.jar"
WALA_UTIL_JAR = f"{RLFIXER_ROOT}/lib/com.ibm.wala.util-1.5.7.jar"
JAVAPARSER_JAR = f"{RLFIXER_ROOT}/lib/javaparser-core-3.24.7.jar"
RLFIXER_JARS_ROOT = f"{RLFIXER_ROOT}/lib"
FILE_WITH_APP_CLASSES = "info/classes"
FILE_WITH_SRCS= "info/sources"

# set the wala jars
class_path_string = f'{RLFIXER_JARS_ROOT}/*:{COMPILED_FOLDER}'
os.environ["CLASSPATH"] = class_path_string


results_file = RESULT_LOCATION
if (os.stat(results_file).st_size == 0):
    exit(0)
warnings_list = None
if TOOL == CHECKERFRAMEWORK:
    warnings_list = get_checkerframework_warnings(results_file, BENCHMARKS_FOLDER)

# construct the warning string parameter
warnings_string = ""
for warning in warnings_list:
    # remove the project name from the file name
    shortened_filename_array = warning.filename.split("/")[3:]
    shortened_filename = "/".join(shortened_filename_array)
    # create a comma and # separated string representing all warnings
    warnings_string += f'{shortened_filename},{warning.line_number},{warning.method},{str(warning.is_owning_overwrite).strip()}#'
    # print(warnings_string)


# skip completed benchmarks
benchmark_name = os.path.basename(results_file.rstrip('/'))[:-4]

benchmark_path = os.path.join(BENCHMARKS_FOLDER,benchmark_name) 


#Get jar file
jarfile = ''
for file in os.listdir(os.path.join(benchmark_path,"jarfile")):
    if file.endswith(".jar"):
        jarfile = file
jarfile_path = os.path.join(benchmark_path,("jarfile/" + jarfile))


#get file with application classes list
appclasses_file = os.path.join(benchmark_path,FILE_WITH_APP_CLASSES)
srcs_file = os.path.join(benchmark_path,FILE_WITH_SRCS)
# construct the commands
wala_command = ("java"
    # + " " + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005" 
    + " " + DRIVER_CLASS
    + " -classpath"
    + " " + jarfile_path
    + " -warnings"
    + " \"" + warnings_string
    + "\" -appClasses"
    + " " + appclasses_file
    + " -projectDir"
    + " " + benchmark_path 
    + " -srcFiles"
    + " " + srcs_file
    + " -debugOutput"
    + " " + args.debug_output + "/" + benchmark_name + ".txt"
    + " -wpiOutDir"
    + " " + WPI_OUT_DIR
    + " > " +  OUTPUT_FOLDER + "/" + benchmark_name + ".txt"
)
empty_file_command = ("touch "
    +  OUTPUT_FOLDER
    + "/" + benchmark_name + ".txt"
)

# execute the right command based on whether there are any errors.
if len(warnings_list) > 0:
    os.system(wala_command)
else:
    os.system(empty_file_command)



