package vn.chamcong.iot.model
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
class PersonnelRulesTest {
 @Test fun retiredEmployeeDisappearsStartingNextMonth() {
  val employee = Employee(id="e1", active=false, terminationDate="2026-09-27")
  assertTrue(employee.visibleOutsideRetiredList(YearMonth.of(2026, 9)))
  assertFalse(employee.visibleOutsideRetiredList(YearMonth.of(2026, 10)))
  assertTrue(employee.copy(active=true).visibleOutsideRetiredList(YearMonth.of(2026, 10)))
 }
 @Test fun skipsExistingCodes() { assertEquals("NV0013", nextEmployeeCode(3, listOf("NV0012", "OLD-99"))) }
 @Test fun neverReusesRetiredSequence() { assertEquals("NV0101", nextEmployeeCode(100, listOf("NV0001"))) }
 @Test fun snapshotSurvivesChanges() {
  val e = Employee(id="a", code="NV0001", fullName="An", baseSalary=80000)
  val slip = createPayroll(e, "2026-09", 100.0, 500000, 200000)
  assertFalse(e.copy(active=false, baseSalary=9000000).active)
  assertEquals(8300000L, slip.netSalary)
  assertEquals("An", slip.employeeName)
  assertEquals(8000000L, slip.baseSalary)
 }
 @Test(expected=IllegalArgumentException::class) fun invalidMonth() { createPayroll(Employee(id="a"), "2026-13", 0.0, 0, 0) }
 @Test(expected=IllegalArgumentException::class) fun negativeBonus() { createPayroll(Employee(id="a"), "2026-09", 1.0, -1, 0) }
}
