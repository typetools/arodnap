// The generated source is analyzed and reported, but neither repaired nor in the patch.
import groovy.json.JsonSlurper

def out = new File(basedir, 'target/arodnap')
def report = new JsonSlurper().parse(new File(out, 'report.json'))
assert report.success : report.error
def leaks = report.leaks.warnings.collectEntries { [(it.file): it] }
assert leaks['target/generated-sources/demo/demo/Generated.java'].reason == 'generated'
assert leaks['src/main/java/demo/FirstByte.java'].status == 'fixed'
def manifest = new JsonSlurper().parse(new File(out, 'patches/manifest.json'))
assert manifest.patches[0].changed_files == ['src/main/java/demo/FirstByte.java']
