/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.suites

import address.ViewConfig
import address.outcome.SelectedAddress
import com.pyruby.stubserver.StubMethod
import it.helper.{AppServerTestApi, Context}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.scalatestplus.play._
import play.api.Application
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.address.v2.Countries._
import uk.gov.hmrc.address.v2._
import uk.gov.hmrc.util.JacksonMapper._

//-------------------------------------------------------------------------------------------------
// This is a long test file to ensure that everything runs in sequence, not overlapping.
// It is also important to start/stop embedded stubs cleanly.
//
// Use the Folds, Luke!!!
//-------------------------------------------------------------------------------------------------

class UkSuite(val context: Context)(implicit val app: Application) extends PlaySpec with AppServerTestApi {

  private def addressLookupStub = context.addressLookupStub

  private def keystoreStub = context.keystoreStub

  private def appContext = context.appContext

  private val en = "en"
  private val NewcastleUponTyne = Some("Newcastle upon Tyne")
  private val TyneAndWear = Some("Tyne & Wear")
  private val NE1_6JN = "NE1 6JN"
  private val lcc = LocalCustodian(123, "Town")
  private val allTags = ViewConfig.cfg.keys.toList.sorted

  val se1_9py = AddressRecord("GB10091836674", Some(10091836674L), Address(List("Dorset House 27-45", "Stamford Street"), Some("London"), None, "SE1 9PY", Some(England), UK), Some(lcc), en)

  // This sample is a length-2 postcode
  val ne1_6jn_a = AddressRecord("GB4510737202", Some(4510737202L), Address(List("11 Market Street"), NewcastleUponTyne, TyneAndWear, NE1_6JN, Some(England), UK), Some(lcc), en)
  val ne1_6jn_b = AddressRecord("GB4510141231", Some(4510141231L), Address(List("Royal House 5-7", "Market Street"), NewcastleUponTyne, TyneAndWear, NE1_6JN, Some(England), UK), Some(lcc), en)

  val ne15xdLike = AddressRecord("GB4510123533", Some(4510123533L),
    Address(List("10 Taylors Court", "Monk Street", "Byker"),
      Some("Newcastle upon Tyne"), Some("Northumberland"), "NE1 5XD", Some(Countries.England), Countries.UK),
    Some(LocalCustodian(123, "Tyne & Wear")), "en")
  val edited = Address(List("10b Taylors Court", "Monk Street", "Byker"),
    Some("Newcastle upon Tyne"), Some("Northumberland"), "NE1 5XD", Some(Countries.England), Countries.UK)
  val sr = SelectedAddress(Some(ne15xdLike), Some(edited), None)

  implicit private val ec = scala.concurrent.ExecutionContext.Implicits.global

  "entry form errors" must {
    "when postcode is left blank, remain on the entry form" in {
      keystoreStub.clearExpectations()
      val se1_9py_withoutEdits = SelectedAddress(Some(se1_9py), None, None)
      val nfaWithoutEdits = SelectedAddress(None, None, None, None, true)

      for (tag <- allTags) {
        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(tag)
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid: String = hiddenGuidValue(doc1)

        //---------- confirmation ----------
        val form1NoFixedAddress = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "postcode" -> "")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1NoFixedAddress, cookies: _*)

        keystoreStub.verify()
        addressLookupStub.verify()
        verifyEntryForm(response2, 400)
      }
    }
  }


  "uk address happy-path journeys" must {

    "journey 1: get form without params, post form with no-fixed-address" in {
      val tag = "j0"
      keystoreStub.clearExpectations()
      val se1_9py_withoutEdits = SelectedAddress(Some(se1_9py), None, None)
      val nfaWithoutEdits = SelectedAddress(None, None, None, None, true)

      //---------- entry form ----------
      val (cookies, doc1) = step1EntryForm(tag)
      val csrfToken = hiddenCsrfTokenValue(doc1)
      val guid: String = hiddenGuidValue(doc1)

      //---------- confirmation ----------
      keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/$guid")) thenReturn(200, "application/json", keystoreResponseString(tag, se1_9py_withoutEdits))

      val form1NoFixedAddress = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
        "no-fixed-address" -> "true", "house-name-number" -> "", "postcode" -> "")
      val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1NoFixedAddress, cookies: _*)

//      keystoreStub.verify()
      addressLookupStub.verify()
      expectConfirmationPage(response2)
    }


    "journey 2: postcode entered; two proposals seen; first one picked without editing" in {
      for (tag <- allTags) {
        addressLookupStub.clearExpectations()
        keystoreStub.clearExpectations()
        val ne1_6jn_withoutEdits = SelectedAddress(Some(ne1_6jn_a), None, None)

        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid = hiddenGuidValue(doc1)
        assert(guid === "abc123")

        //---------- proposal form ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a, ne1_6jn_b)))

        val form1PostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "postcode" -> "NE1 6JN")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1PostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        expectProposalForm(response2, 2, guid, "", "NE1 6JN")

        //---------- confirmation ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510737202")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_a))
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val form2PostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "prev-house-name-number" -> "", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510737202")
        val response3 = request("POST", s"$appContext/uk/addresses/$tag/select", form2PostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        expectConfirmationPage(response3)

        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

        keystoreStub.verify()
        assert(outcomeResponse.status === 200)
        val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
        assert(outcome === ne1_6jn_withoutEdits)
      }
    }


    "journey 3a: house number and postcode entered; single proposal seen and accepted without editing" in {
      for (tag <- allTags) {
        addressLookupStub.clearExpectations()
        keystoreStub.clearExpectations()
        val ne1_6jn_withoutEdits = SelectedAddress(Some(ne1_6jn_a), None, None)

        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid = hiddenGuidValue(doc1)
        assert(guid === "abc123")

        //---------- proposal form ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a)))

        val form1NameAndPostcode = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "postcode" -> "NE16JN")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1NameAndPostcode, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response2, 1, guid, "11", "NE1 6JN")

        //---------- confirmation ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510737202")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_a))
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val form2PostcodeAndRadio = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "prev-house-name-number" -> "11", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510737202")
        val response3 = request("POST", s"$appContext/uk/addresses/$tag/select", form2PostcodeAndRadio, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        val page = expectConfirmationPage(response3)
        assert(page.select("#confirmation .addr .norm").text.trim === ne1_6jn_a.address.line1, response3.body)
        assert(page.select("#confirmation .town .norm").text.trim === ne1_6jn_a.address.town.get, response3.body)
        assert(page.select("#confirmation .postcode .norm").text.trim === ne1_6jn_a.address.postcode, response3.body)
        assert(page.select("#confirmation .county .norm").text.trim === ne1_6jn_a.address.county.get, response3.body)
        assert(page.select("#confirmation .subd .norm").text.trim === ne1_6jn_a.address.subdivision.get.name, response3.body)
        assert(page.select("#confirmation .country .norm").text.trim === ne1_6jn_a.address.country.code, response3.body)

        assert(page.select("#confirmation .address .user").text.trim === "", response3.body)
        assert(page.select("#confirmation .county .user").text.trim === "", response3.body)

        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

        keystoreStub.verify()
        assert(outcomeResponse.status === 200)
        val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
        assert(outcome === ne1_6jn_withoutEdits)
      }
    }


    "journey 3b: house number and postcode entered; single proposal accepted and edited" in {
      val tag = "j0"
      addressLookupStub.clearExpectations()
      keystoreStub.clearExpectations()
      val ne1_6jn_edited = ne1_6jn_a.address.copy(lines = List("11B Market Street"), county = Some("Northumbria"))
      val ne1_6jn_withEdits = SelectedAddress(Some(ne1_6jn_a), Some(ne1_6jn_edited), None)

      //---------- entry form ----------
      val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
      val csrfToken = hiddenCsrfTokenValue(doc1)
      val guid = hiddenGuidValue(doc1)
      assert(guid === "abc123")

      //---------- proposal form ----------
      addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a)))

      val form1NameAndPostcode = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
        "house-name-number" -> "11", "postcode" -> "NE16JN")
      val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1NameAndPostcode, cookies: _*)

      addressLookupStub.verify()
      expectProposalForm(response2, 1, guid, "11", "NE1 6JN")

      //---------- make edit request ----------
      addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a)))

      val response3 = request("GET", s"$appContext/uk/addresses/$tag/get-proposals/11/NE1%206JN/$guid?continue=confirmation&editId=GB4510737202", cookies: _*)

      addressLookupStub.verify()
      keystoreStub.verify()
      expectProposalForm(response3, 1, guid, "11", "NE1 6JN")

      //---------- confirmation ----------
      addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510737202")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_a))
      keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withEdits))

      val form2PostcodeAndRadio = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
        "house-name-number" -> "11", "prev-house-name-number" -> "11", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510737202",
        "address-lines" -> "11B Market Street", "town" -> "Newcastle upon Tyne", "county" -> "Northumbria")

      val response4 = request("POST", s"$appContext/uk/addresses/$tag/select", form2PostcodeAndRadio, cookies: _*)

      addressLookupStub.verify()
      keystoreStub.verify()
      val page = expectConfirmationPage(response4)
      assert(page.select("#confirmation .addr .norm").text.trim === "11 Market Street", response4.body)
      assert(page.select("#confirmation .addr .user").text.trim === "11B Market Street", response4.body)
      assert(page.select("#confirmation .county .norm").text.trim === "Tyne & Wear", response4.body)
      assert(page.select("#confirmation .county .user").text.trim === "Northumbria", response4.body)

      keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withEdits))

      val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

      keystoreStub.verify()
      assert(outcomeResponse.status === 200)
      val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
      assert(outcome === ne1_6jn_withEdits)
    }


    "journey 4: postcode entered; two proposal seen; house number added; one proposal seen and picked without editing" in {
      for (tag <- allTags) {
        addressLookupStub.clearExpectations()
        keystoreStub.clearExpectations()
        val ne1_6jn_withoutEdits = SelectedAddress(Some(ne1_6jn_a), None, None)

        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid = hiddenGuidValue(doc1)
        assert(guid === "abc123")

        //---------- proposal form 1 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a, ne1_6jn_b)))

        val form1PostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "postcode" -> "NE1 6JN")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1PostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response2, 2, guid, "", "NE1 6JN")

        //---------- proposal form 2 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a, ne1_6jn_b)))

        val form2AHouseAndPostcode = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "prev-house-name-number" -> "", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN")
        val response3 = request("POST", s"$appContext/uk/addresses/$tag/select", form2AHouseAndPostcode, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response3, 2, guid, "11", "NE1 6JN")

        //---------- confirmation ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510737202")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_a))
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val form2BRadioSelected = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "prev-house-name-number" -> "11", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510737202")
        val response4 = request("POST", s"$appContext/uk/addresses/$tag/select", form2BRadioSelected, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        expectConfirmationPage(response4)

        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

        keystoreStub.verify()
        assert(outcomeResponse.status === 200)
        val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
        assert(outcome === ne1_6jn_withoutEdits)
      }
    }


    "journey 5: house number and postcode entered; single proposal seen; house number changed; single proposal seen and accepted without editing" in {
      for (tag <- allTags) {
        addressLookupStub.clearExpectations()
        keystoreStub.clearExpectations()
        val ne1_6jn_withoutEdits = SelectedAddress(Some(ne1_6jn_a), None, None)

        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid = hiddenGuidValue(doc1)
        assert(guid === "abc123")

        //---------- proposal form 1 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a)))

        val form1HouseAndPostcode = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "postcode" -> "NE1 6JN")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1HouseAndPostcode, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response2, 1, guid, "11", "NE1 6JN")

        //---------- proposal form 2 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=Royal")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_b)))

        val form2APostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "Royal", "prev-house-name-number" -> "11", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN")
        val response3 = request("POST", s"$appContext/uk/addresses/$tag/select", form2APostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response3, 1, guid, "Royal", "NE1 6JN")

        //---------- confirmation ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510141231")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_b))
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val form2BPostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "Royal", "prev-house-name-number" -> "Royal", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510141231")
        val response4 = request("POST", s"$appContext/uk/addresses/$tag/select", form2BPostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        expectConfirmationPage(response4)

        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

        keystoreStub.verify()
        assert(outcomeResponse.status === 200)
        val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
        assert(outcome === ne1_6jn_withoutEdits)
      }
    }


    "journey 6: house number and postcode entered; single proposal seen; house number erased; two proposals seen; first accepted without editing" in {
      for (tag <- allTags) {
        addressLookupStub.clearExpectations()
        keystoreStub.clearExpectations()
        val ne1_6jn_withoutEdits = SelectedAddress(Some(ne1_6jn_a), None, None)

        //---------- entry form ----------
        val (cookies, doc1) = step1EntryForm(s"$tag?id=abc123")
        val csrfToken = hiddenCsrfTokenValue(doc1)
        val guid = hiddenGuidValue(doc1)
        assert(guid === "abc123")

        //---------- proposal form 1 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN&filter=11")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a)))

        val form1HouseAndPostcode = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "11", "postcode" -> "NE1 6JN")
        val response2 = request("POST", s"$appContext/uk/addresses/$tag/propose", form1HouseAndPostcode, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response2, 1, guid, "11", "NE1 6JN")

        //---------- proposal form 2 ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses?postcode=NE1+6JN")) thenReturn(200, "application/json", writeValueAsString(List(ne1_6jn_a, ne1_6jn_b)))

        val form2APostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "prev-house-name-number" -> "11", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN")
        val response3 = request("POST", s"$appContext/uk/addresses/$tag/select", form2APostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        expectProposalForm(response3, 2, guid, "", "NE1 6JN")

        //---------- confirmation ----------
        addressLookupStub.expect(StubMethod.get("/v2/uk/addresses/GB4510737202")) thenReturn(200, "application/json", writeValueAsString(ne1_6jn_a))
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val form2BPostcodeOnly = Map("csrfToken" -> csrfToken, "guid" -> guid, "continue-url" -> "confirmation", "country-code" -> "UK",
          "house-name-number" -> "", "prev-house-name-number" -> "", "postcode" -> "NE1 6JN", "prev-postcode" -> "NE1 6JN", "radio-inline-group" -> "GB4510737202")
        val response4 = request("POST", s"$appContext/uk/addresses/$tag/select", form2BPostcodeOnly, cookies: _*)

        addressLookupStub.verify()
        keystoreStub.verify()
        expectConfirmationPage(response4)

        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/abc123")) thenReturn(200, "application/json", keystoreResponseString(tag, ne1_6jn_withoutEdits))

        val outcomeResponse = get(s"$appContext/outcome/$tag/$guid")

        keystoreStub.verify()
        assert(outcomeResponse.status === 200)
        val outcome = readValue(outcomeResponse.body, classOf[SelectedAddress])
        assert(outcome === ne1_6jn_withoutEdits)
      }
    }
  }


  "uk address error journeys" must {
    "landing unexpectedly on the proposal form causes redirection to the blank form" in {
      addressLookupStub.clearExpectations()
      keystoreStub.clearExpectations()

      for (tag <- allTags) {
        val response = get(appContext + s"/uk/addresses/$tag/get-proposals/-/-/abc123")
        verifyEntryForm(response, 400)

        keystoreStub.verify()
        addressLookupStub.verify()
      }
    }

    "landing unexpectedly on the confirmation page causes redirection to the blank form" in {
      addressLookupStub.clearExpectations()

      for (tag <- allTags) {
        keystoreStub.clearExpectations()
        keystoreStub.expect(StubMethod.get(s"/keystore/address-lookup/a1c5d2ba")) thenReturn(404, "text/plain", "Not found")

        val response = get(appContext + s"/uk/addresses/$tag/confirmation?id=a1c5d2ba")
        verifyEntryForm(response)

        keystoreStub.verify()
        addressLookupStub.verify()
      }
    }
  }


  private def expectProposalForm(response: WSResponse, expectedSize: Int, expectedGuid: String, expectedHouse: String, expectedPostcode: String) {
    assert(response.status === 200, response.body)
    val doc = Jsoup.parse(response.body)
    assert(doc.select("body.proposal-form").size === 1, response.body)
    assert(doc.select("table#Address-table tbody tr").size === expectedSize, response.body)
    assert(hiddenGuidValue(doc) === expectedGuid)
    assert(textBoxValue(doc, "house-name-number") === expectedHouse)
    assert(textBoxValue(doc, "postcode") === expectedPostcode)
    assert(hiddenValue(doc, "prev-house-name-number") === expectedHouse)
    assert(hiddenValue(doc, "prev-postcode") === expectedPostcode)
  }

  private def expectConfirmationPage(response: WSResponse) = {
    assert(response.status === 200)
    val doc = Jsoup.parse(response.body)
    assert(doc.select("body.confirmation-page").size === 1, response.body)
    doc
  }

  private def step1EntryForm(params: String = ""): (Seq[(String, String)], Document) = {
    val response = get(context.appContext + "/uk/addresses/" + params)
    verifyEntryForm(response)
  }

  private def verifyEntryForm(response: WSResponse, expectedCode: Int = 200): (Seq[(String, String)], Document) = {
    assert(response.status === expectedCode)
    val cookies = newCookies(response)
    val doc = Jsoup.parse(response.body)
    assert(doc.select("body.entry-form").size === 1, response.body)
    (cookies, doc)
  }

  private def keystoreResponseJson(tag: String, sa: SelectedAddress) = Json.toJson(Map("data" -> Map(tag -> sa)))

  private def keystoreResponseString(tag: String, sa: SelectedAddress) = Json.stringify(keystoreResponseJson(tag, sa))

  private def i1Json(tag: String, i: International) = keystoreResponseString(tag, SelectedAddress(international = Some(i)))

  private def newCookies(response: WSResponse) = response.cookies.map(c => c.name.get + "=" + c.value.get).map("cookie" -> _)

  private def hiddenCsrfTokenValue(doc: Document) = hiddenValue(doc, "csrfToken")

  private def hiddenGuidValue(doc: Document) = hiddenValue(doc, "guid")

  private def hiddenValue(doc: Document, name: String) = doc.select(s"input[type=hidden][name=$name]").attr("value")

  private def textBoxValue(doc: Document, name: String) = doc.select(s"input[type=text][name=$name]").attr("value")

}
