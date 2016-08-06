package io.reactors
package debugger



import org.openqa.selenium._
import org.openqa.selenium.chrome._
import org.openqa.selenium.interactions._
import org.openqa.selenium.support.ui._



object DebuggerTest {
  def main(args: Array[String]) {
    // Initialize driver.
    System.setProperty("webdriver.chrome.driver", "tools/chromedriver")
    val options = new ChromeOptions
    options.setBinary("/usr/bin/chromium-browser")
    val driver = new ChromeDriver(options)

    // Initialize debugger.
    val config = ReactorSystem.customConfig("""
      debug-api = {
        name = "io.reactors.debugger.WebDebugger"
      }
    """)
    val bundle = new ReactorSystem.Bundle(Scheduler.default, config)
    val system = new ReactorSystem("web-debugger-test-system", bundle)

    var error: Throwable = null
    try {
      // Run tests.
      runTests(driver, system)
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        error = t
    } finally {
      // Quit.
      Thread.sleep(2000)
      driver.quit()
      system.shutdown()
      Thread.sleep(1000)
      if (error == null) System.exit(0)
      else System.exit(1)
    }
  }

  def runTests(driver: WebDriver, system: ReactorSystem) {
    driver.get("localhost:8888")

    // Run shell tests.
    runShellTests(driver, system)
  }

  def runShellTests(driver: WebDriver, system: ReactorSystem) {
    val shellButton = driver.findElement(By.id("x-debugger-button-shell"))
    shellButton.click()

    // TODO: Replace with waiting for the shell to become ready.
    Thread.sleep(5000)

    // Create temporary reactor.
    val shellContainer = driver.findElement(By.className("x-shell-container"))
    shellContainer.click()

    def shellCommand(text: String) {
      val cmdline = driver.findElement(By.className("cmd"))
      val actions = new Actions(driver)
      actions.moveToElement(cmdline)
      actions.click()
      actions.sendKeys(text)
      actions.sendKeys(Keys.RETURN)
      actions.build().perform()
    }

    shellCommand("import scala.concurrent.duration._")
    shellCommand("val ch = system.spawn(Reactor[String] { self => })")

    // Wait for easier debugging.
    Thread.sleep(1000)
  }
}
