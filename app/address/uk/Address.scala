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

package address.uk

import java.util.regex.Pattern

/**
  * Address typically represents a postal address.
  * For UK addresses, 'town' will always be present.
  * For non-UK addresses, 'town' may be absent and there may be an extra line instead.
  */
case class Address(lines: List[String],
                   town: Option[String],
                   county: Option[String],
                   postcode: String,
                   subdivision: Option[String],
                   country: Country) {

  def isValid = lines.nonEmpty && lines.size <= (if (town.isEmpty) 4 else 3)

  def nonEmptyFields: List[String] = lines ::: town.toList ::: county.toList ::: List(postcode)

  /** Gets a conjoined representation, excluding the country. */
  def printable(separator: String): String = nonEmptyFields.mkString(separator)

  /** Gets a single-line representation, excluding the country. */
  lazy val printable: String = printable(", ")

  def line1 = if (lines.nonEmpty) lines.head else ""

  def line2 = if (lines.size > 1) lines(1) else ""

  def line3 = if (lines.size > 2) lines(2) else ""

  def line4 = if (lines.size > 3) lines(3) else ""

  def longestLineLength = nonEmptyFields.map(_.length).max

  private def limit35(str: String): String = {
    var s = str
    while (s.length > Address.maxLineLength && s.indexOf(", ") > 0) {
      s = s.replaceFirst(", ", ",")
    }
    if (s.length > Address.maxLineLength) {
      s = s.substring(0, Address.maxLineLength).trim
      if (Address.danglingLetter.matcher(s).matches()) {
        s = s.substring(0, s.length - 2)
      }
      s
    }
    else s
  }

  def truncatedAddress = Address(lines.map(limit35), town.map(limit35), county.map(limit35), postcode, subdivision, country)
}


object Address {
  val maxLineLength = 35
  val danglingLetter = Pattern.compile(".* [A-Z]$")
}


/** Represents a country as per ISO3166. */
case class Country(
                    // ISO3166-1 or ISO3166-2 code, e.g. "GB" or "GB-ENG" (note that "GB" is the official
                    // code for UK although "UK" is a reserved synonym and may be used instead)
                    // See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
                    // and https://en.wikipedia.org/wiki/ISO_3166-2:GB
                    code: String,
                    // The printable name for the country, e.g. "United Kingdom"
                    name: String)

object Countries {
  // note that "GB" is the official ISO code for UK, although "UK" is a reserved synonym and is less confusing
  val UK = Country("UK", "United Kingdom")
}