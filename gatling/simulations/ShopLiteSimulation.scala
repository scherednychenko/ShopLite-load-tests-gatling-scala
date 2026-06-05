import io.gatling.core.Predef._
import io.gatling.http.Predef._

import java.util.UUID
import scala.concurrent.duration._
import scala.util.Random

/**
 * ShopLite load test — Gatling (Scala DSL).
 *
 * Mirrors the JMeter/k6/Locust scenario: Browse catalog -> Add to cart (N items)
 * -> Checkout, against placeholder endpoints served by the local mock backend.
 */
class ShopLiteSimulation extends Simulation {

  private val baseUrl  = sys.env.getOrElse("BASE_URL", "http://localhost:8080")
  private val cartSize = sys.env.getOrElse("CART_SIZE", "10").toInt
  private val vus      = sys.env.getOrElse("VUS", "10").toInt

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  private val products = Array("1001", "1002", "1003")

  // Per-pass data: unique guest email + a random product.
  private val guestFeeder = Iterator.continually(Map[String, Any](
    "email"     -> s"qa.perf+${UUID.randomUUID().toString.take(8)}@example.com",
    "productId" -> products(Random.nextInt(products.length))
  ))

  private val browse = exec(
    http("TX_Browse_Catalog")
      .get("/api/catalog?page=1&size=20")
      .check(status.is(200))
  ).pause(300.milliseconds, 1200.milliseconds)

  private val addToCart = repeat(cartSize) {
    feed(guestFeeder).exec(
      http("TX_Add_To_Cart")
        .post("/api/cart/items")
        .body(StringBody("""{"productId":"#{productId}","qty":1}""")).asJson
        .check(status.in(200, 201))
        .check(jsonPath("$.cartId").saveAs("cartId"))
    ).pause(300.milliseconds, 1200.milliseconds)
  }

  private val checkout = exec(
    http("TX_Checkout_PlaceOrder")
      .post("/api/orders")
      .body(StringBody(
        """{"cartId":"#{cartId}","guest":{"email":"#{email}","firstName":"Perf","lastName":"Guest","phone":"+10000000000"},"shippingAddress":{"country":"HR","city":"Zagreb","addressLine1":"Perf Street 1","zip":"10000"}}"""
      )).asJson
      .check(status.in(200, 201))
  ).pause(300.milliseconds, 1200.milliseconds)

  private val scn = scenario("ShopLite")
    .feed(guestFeeder)
    .exec(browse)
    .exec(addToCart)
    .exec(checkout)

  setUp(
    scn.inject(rampUsers(vus).during(10.seconds))
  ).protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lt(1.0),
      global.responseTime.percentile(95.0).lt(500)
    )
}
