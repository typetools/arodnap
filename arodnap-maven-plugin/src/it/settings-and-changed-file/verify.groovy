// The settings are honored, and apply refuses a file that changed after the repair.
import groovy.json.JsonSlurper

def out = new File(basedir, 'target/custom')
def report = new JsonSlurper().parse(new File(out, 'report.json'))
assert report.success : report.error
assert !new File(basedir, 'target/arodnap').exists()
def fields = new JsonSlurper().parse(new File(out, 'stages/field_transformations/stage_result.json'))
assert fields.notes == ['Field transformations are off.'] && !fields.changed
assert report.leaks.summary.fixed == 1

def source = new File(basedir, 'src/main/java/demo/FirstByte.java').text
assert source.contains('// edited after the repair')
assert !source.contains('try (')
assert new File(basedir, 'build.log').text.contains('src/main/java/demo/FirstByte.java has changed since the patch was made')
