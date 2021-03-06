/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.util;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * <p>
 * FastDateFormat is a fast and thread-safe version of
 * {@link java.text.SimpleDateFormat}.
 * </p>
 * 
 * <p>
 * This class can be used as a direct replacement to
 * <code>SimpleDateFormat</code> in most formatting situations. This class is
 * especially useful in multi-threaded server environments.
 * <code>SimpleDateFormat</code> is not thread-safe in any JDK version, nor will
 * it be as Sun have closed the bug/RFE.
 * </p>
 * 
 * <p>
 * Only formatting is supported, but all patterns are compatible with
 * SimpleDateFormat (except time zones - see below).
 * </p>
 * 
 * <p>
 * Java 1.4 introduced a new pattern letter, <code>'Z'</code>, to represent time
 * zones in RFC822 format (eg. <code>+0800</code> or <code>-1100</code>). This
 * pattern letter can be used here (on all JDK versions).
 * </p>
 * 
 * <p>
 * In addition, the pattern <code>'ZZ'</code> has been made to represent ISO8601
 * full format time zones (eg. <code>+08:00</code> or <code>-11:00</code>). This
 * introduces a minor incompatibility with Java 1.4, but at a gain of useful
 * functionality.
 * </p>
 * 
 * @author Apache Software Foundation
 * @author TeaTrove project
 * @author Brian S O'Neill
 * @author Sean Schofield
 * @author Gary Gregory
 * @author Nikolay Metchev
 * @since 2.0
 * @version $Id$
 */
public class FastDateFormat extends Format {
	// A lot of the speed in this class comes from caching, but some comes
	// from the special int to StringBuffer conversion.
	//
	// The following produces a padded 2 digit number:
	// buffer.append((char)(value / 10 + '0'));
	// buffer.append((char)(value % 10 + '0'));
	//
	// Note that the fastest append to StringBuffer is a single char (used
	// here).
	// Note that Integer.toString() is not called, the conversion is simply
	// taking the value and adding (mathematically) the ASCII value for '0'.
	// So, don't change this code! It works and is very fast.

	/**
	 * Required for serialization support.
	 * 
	 * @see java.io.Serializable
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * FULL locale dependent date or time style.
	 */
	public static final int FULL = DateFormat.FULL;
	/**
	 * LONG locale dependent date or time style.
	 */
	public static final int LONG = DateFormat.LONG;
	/**
	 * MEDIUM locale dependent date or time style.
	 */
	public static final int MEDIUM = DateFormat.MEDIUM;
	/**
	 * SHORT locale dependent date or time style.
	 */
	public static final int SHORT = DateFormat.SHORT;

	// @GuardedBy("this")
	private static String cDefaultPattern; // lazily initialised by
											// getInstance()

	private static final Map<FastDateFormat, FastDateFormat> cInstanceCache = new HashMap<FastDateFormat, FastDateFormat>(7);
	private static final Map<Object, FastDateFormat> cDateInstanceCache = new HashMap<Object, FastDateFormat>(7);
	private static final Map<Object, FastDateFormat> cTimeInstanceCache = new HashMap<Object, FastDateFormat>(7);
	private static final Map<Object, FastDateFormat> cDateTimeInstanceCache = new HashMap<Object, FastDateFormat>(7);
	private static final Map<Object, String> cTimeZoneDisplayCache = new HashMap<Object, String>(7);

	/**
	 * The pattern.
	 */
	private final String mPattern;
	/**
	 * The time zone.
	 */
	private final TimeZone mTimeZone;
	/**
	 * Whether the time zone overrides any on Calendars.
	 */
	private final boolean mTimeZoneForced;
	/**
	 * The locale.
	 */
	private final Locale mLocale;
	/**
	 * Whether the locale overrides the default.
	 */
	private final boolean mLocaleForced;
	/**
	 * The parsed rules.
	 */
	private transient Rule[] mRules;
	/**
	 * The estimated maximum length.
	 */
	private transient int mMaxLengthEstimate;

	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets a formatter instance using the default pattern in the default
	 * locale.
	 * </p>
	 * 
	 * @return a date/time formatter
	 */
	public static FastDateFormat getInstance() {
		return getInstance(getDefaultPattern(), null, null);
	}

	/**
	 * <p>
	 * Gets a formatter instance using the specified pattern in the default
	 * locale.
	 * </p>
	 * 
	 * @param pattern
	 *            {@link java.text.SimpleDateFormat} compatible pattern
	 * @return a pattern based date/time formatter
	 * @throws IllegalArgumentException
	 *             if pattern is invalid
	 */
	public static FastDateFormat getInstance(final String pattern) {
		return getInstance(pattern, null, null);
	}

	/**
	 * <p>
	 * Gets a formatter instance using the specified pattern and time zone.
	 * </p>
	 * 
	 * @param pattern
	 *            {@link java.text.SimpleDateFormat} compatible pattern
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @return a pattern based date/time formatter
	 * @throws IllegalArgumentException
	 *             if pattern is invalid
	 */
	public static FastDateFormat getInstance(final String pattern, final TimeZone timeZone) {
		return getInstance(pattern, timeZone, null);
	}

	/**
	 * <p>
	 * Gets a formatter instance using the specified pattern and locale.
	 * </p>
	 * 
	 * @param pattern
	 *            {@link java.text.SimpleDateFormat} compatible pattern
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a pattern based date/time formatter
	 * @throws IllegalArgumentException
	 *             if pattern is invalid
	 */
	public static FastDateFormat getInstance(final String pattern, final Locale locale) {
		return getInstance(pattern, null, locale);
	}

	/**
	 * <p>
	 * Gets a formatter instance using the specified pattern, time zone and
	 * locale.
	 * </p>
	 * 
	 * @param pattern
	 *            {@link java.text.SimpleDateFormat} compatible pattern
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a pattern based date/time formatter
	 * @throws IllegalArgumentException
	 *             if pattern is invalid or <code>null</code>
	 */
	public static synchronized FastDateFormat getInstance(final String pattern, final TimeZone timeZone, final Locale locale) {
		final FastDateFormat emptyFormat = new FastDateFormat(pattern, timeZone, locale);
		FastDateFormat format = cInstanceCache.get(emptyFormat);
		if (format == null) {
			format = emptyFormat;
			format.init(); // convert shell format into usable one
			cInstanceCache.put(format, format); // this is OK!
		}
		return format;
	}

	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets a date formatter instance using the specified style in the default
	 * time zone and locale.
	 * </p>
	 * 
	 * @param style
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @return a localized standard date formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateInstance(final int style) {
		return getDateInstance(style, null, null);
	}

	/**
	 * <p>
	 * Gets a date formatter instance using the specified style and locale in
	 * the default time zone.
	 * </p>
	 * 
	 * @param style
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard date formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateInstance(final int style, final Locale locale) {
		return getDateInstance(style, null, locale);
	}

	/**
	 * <p>
	 * Gets a date formatter instance using the specified style and time zone in
	 * the default locale.
	 * </p>
	 * 
	 * @param style
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @return a localized standard date formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateInstance(final int style, final TimeZone timeZone) {
		return getDateInstance(style, timeZone, null);
	}

	/**
	 * <p>
	 * Gets a date formatter instance using the specified style, time zone and
	 * locale.
	 * </p>
	 * 
	 * @param style
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard date formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date pattern defined
	 */
	public static synchronized FastDateFormat getDateInstance(final int style, final TimeZone timeZone, Locale locale) {
		Object key = Integer.valueOf(style);
		if (timeZone != null) {
			key = new Pair(key, timeZone);
		}

		if (locale == null) {
			locale = Locale.getDefault();
		}

		key = new Pair(key, locale);

		FastDateFormat format = cDateInstanceCache.get(key);
		if (format == null) {
			try {
				final SimpleDateFormat formatter = (SimpleDateFormat) DateFormat.getDateInstance(style, locale);
				final String pattern = formatter.toPattern();
				format = getInstance(pattern, timeZone, locale);
				cDateInstanceCache.put(key, format);

			} catch (final ClassCastException ex) {
				throw new IllegalArgumentException("No date pattern for locale: " + locale);
			}
		}
		return format;
	}

	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets a time formatter instance using the specified style in the default
	 * time zone and locale.
	 * </p>
	 * 
	 * @param style
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @return a localized standard time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getTimeInstance(final int style) {
		return getTimeInstance(style, null, null);
	}

	/**
	 * <p>
	 * Gets a time formatter instance using the specified style and locale in
	 * the default time zone.
	 * </p>
	 * 
	 * @param style
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getTimeInstance(final int style, final Locale locale) {
		return getTimeInstance(style, null, locale);
	}

	/**
	 * <p>
	 * Gets a time formatter instance using the specified style and time zone in
	 * the default locale.
	 * </p>
	 * 
	 * @param style
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted time
	 * @return a localized standard time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getTimeInstance(final int style, final TimeZone timeZone) {
		return getTimeInstance(style, timeZone, null);
	}

	/**
	 * <p>
	 * Gets a time formatter instance using the specified style, time zone and
	 * locale.
	 * </p>
	 * 
	 * @param style
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted time
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no time pattern defined
	 */
	public static synchronized FastDateFormat getTimeInstance(final int style, final TimeZone timeZone, Locale locale) {
		Object key = Integer.valueOf(style);
		if (timeZone != null) {
			key = new Pair(key, timeZone);
		}
		if (locale != null) {
			key = new Pair(key, locale);
		}

		FastDateFormat format = cTimeInstanceCache.get(key);
		if (format == null) {
			if (locale == null) {
				locale = Locale.getDefault();
			}

			try {
				final SimpleDateFormat formatter = (SimpleDateFormat) DateFormat.getTimeInstance(style, locale);
				final String pattern = formatter.toPattern();
				format = getInstance(pattern, timeZone, locale);
				cTimeInstanceCache.put(key, format);

			} catch (final ClassCastException ex) {
				throw new IllegalArgumentException("No date pattern for locale: " + locale);
			}
		}
		return format;
	}

	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets a date/time formatter instance using the specified style in the
	 * default time zone and locale.
	 * </p>
	 * 
	 * @param dateStyle
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeStyle
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @return a localized standard date/time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date/time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateTimeInstance(final int dateStyle, final int timeStyle) {
		return getDateTimeInstance(dateStyle, timeStyle, null, null);
	}

	/**
	 * <p>
	 * Gets a date/time formatter instance using the specified style and locale
	 * in the default time zone.
	 * </p>
	 * 
	 * @param dateStyle
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeStyle
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard date/time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date/time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateTimeInstance(final int dateStyle, final int timeStyle, final Locale locale) {
		return getDateTimeInstance(dateStyle, timeStyle, null, locale);
	}

	/**
	 * <p>
	 * Gets a date/time formatter instance using the specified style and time
	 * zone in the default locale.
	 * </p>
	 * 
	 * @param dateStyle
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeStyle
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @return a localized standard date/time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date/time pattern defined
	 * @since 2.1
	 */
	public static FastDateFormat getDateTimeInstance(final int dateStyle, final int timeStyle, final TimeZone timeZone) {
		return getDateTimeInstance(dateStyle, timeStyle, timeZone, null);
	}

	/**
	 * <p>
	 * Gets a date/time formatter instance using the specified style, time zone
	 * and locale.
	 * </p>
	 * 
	 * @param dateStyle
	 *            date style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeStyle
	 *            time style: FULL, LONG, MEDIUM, or SHORT
	 * @param timeZone
	 *            optional time zone, overrides time zone of formatted date
	 * @param locale
	 *            optional locale, overrides system locale
	 * @return a localized standard date/time formatter
	 * @throws IllegalArgumentException
	 *             if the Locale has no date/time pattern defined
	 */
	public static synchronized FastDateFormat getDateTimeInstance(final int dateStyle, final int timeStyle, final TimeZone timeZone, Locale locale) {

		Object key = new Pair(Integer.valueOf(dateStyle), Integer.valueOf(timeStyle));
		if (timeZone != null) {
			key = new Pair(key, timeZone);
		}
		if (locale == null) {
			locale = Locale.getDefault();
		}
		key = new Pair(key, locale);

		FastDateFormat format = cDateTimeInstanceCache.get(key);
		if (format == null) {
			try {
				final SimpleDateFormat formatter = (SimpleDateFormat) DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale);
				final String pattern = formatter.toPattern();
				format = getInstance(pattern, timeZone, locale);
				cDateTimeInstanceCache.put(key, format);

			} catch (final ClassCastException ex) {
				throw new IllegalArgumentException("No date time pattern for locale: " + locale);
			}
		}
		return format;
	}

	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets the time zone display name, using a cache for performance.
	 * </p>
	 * 
	 * @param tz
	 *            the zone to query
	 * @param daylight
	 *            true if daylight savings
	 * @param style
	 *            the style to use <code>TimeZone.LONG</code> or
	 *            <code>TimeZone.SHORT</code>
	 * @param locale
	 *            the locale to use
	 * @return the textual name of the time zone
	 */
	static synchronized String getTimeZoneDisplay(final TimeZone tz, final boolean daylight, final int style, final Locale locale) {
		final Object key = new TimeZoneDisplayKey(tz, daylight, style, locale);
		String value = cTimeZoneDisplayCache.get(key);
		if (value == null) {
			// This is a very slow call, so cache the results.
			value = tz.getDisplayName(daylight, style, locale);
			cTimeZoneDisplayCache.put(key, value);
		}
		return value;
	}

	/**
	 * <p>
	 * Gets the default pattern.
	 * </p>
	 * 
	 * @return the default pattern
	 */
	private static synchronized String getDefaultPattern() {
		if (cDefaultPattern == null) {
			cDefaultPattern = new SimpleDateFormat().toPattern();
		}
		return cDefaultPattern;
	}

	// Constructor
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Constructs a new FastDateFormat.
	 * </p>
	 * 
	 * @param pattern
	 *            {@link java.text.SimpleDateFormat} compatible pattern
	 * @param timeZone
	 *            time zone to use, <code>null</code> means use default for
	 *            <code>Date</code> and value within for <code>Calendar</code>
	 * @param locale
	 *            locale, <code>null</code> means use system default
	 * @throws IllegalArgumentException
	 *             if pattern is invalid or <code>null</code>
	 */
	protected FastDateFormat(final String pattern, TimeZone timeZone, Locale locale) {
		super();
		if (pattern == null)
			throw new IllegalArgumentException("The pattern must not be null");
		mPattern = pattern;

		mTimeZoneForced = timeZone != null;
		if (timeZone == null) {
			timeZone = TimeZone.getDefault();
		}
		mTimeZone = timeZone;

		mLocaleForced = locale != null;
		if (locale == null) {
			locale = Locale.getDefault();
		}
		mLocale = locale;
	}

	/**
	 * <p>
	 * Initializes the instance for first use.
	 * </p>
	 */
	protected void init() {
		final List<Rule> rulesList = parsePattern();
		mRules = rulesList.toArray(new Rule[rulesList.size()]);

		int len = 0;
		for (int i = mRules.length; --i >= 0;) {
			len += mRules[i].estimateLength();
		}

		mMaxLengthEstimate = len;
	}

	// Parse the pattern
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Returns a list of Rules given a pattern.
	 * </p>
	 * 
	 * @return a <code>List</code> of Rule objects
	 * @throws IllegalArgumentException
	 *             if pattern is invalid
	 */
	protected List<Rule> parsePattern() {
		final DateFormatSymbols symbols = new DateFormatSymbols(mLocale);
		final List<Rule> rules = new ArrayList<Rule>();

		final String[] ERAs = symbols.getEras();
		final String[] months = symbols.getMonths();
		final String[] shortMonths = symbols.getShortMonths();
		final String[] weekdays = symbols.getWeekdays();
		final String[] shortWeekdays = symbols.getShortWeekdays();
		final String[] AmPmStrings = symbols.getAmPmStrings();

		final int length = mPattern.length();
		final int[] indexRef = new int[1];

		for (int i = 0; i < length; i++) {
			indexRef[0] = i;
			final String token = parseToken(mPattern, indexRef);
			i = indexRef[0];

			final int tokenLen = token.length();
			if (tokenLen == 0) {
				break;
			}

			Rule rule;
			final char c = token.charAt(0);

			switch (c) {
			case 'G': // era designator (text)
				rule = new TextField(Calendar.ERA, ERAs);
				break;
			case 'y': // year (number)
				if (tokenLen >= 4) {
					rule = selectNumberRule(Calendar.YEAR, tokenLen);
				} else {
					rule = TwoDigitYearField.INSTANCE;
				}
				break;
			case 'M': // month in year (text and number)
				if (tokenLen >= 4) {
					rule = new TextField(Calendar.MONTH, months);
				} else if (tokenLen == 3) {
					rule = new TextField(Calendar.MONTH, shortMonths);
				} else if (tokenLen == 2) {
					rule = TwoDigitMonthField.INSTANCE;
				} else {
					rule = UnpaddedMonthField.INSTANCE;
				}
				break;
			case 'd': // day in month (number)
				rule = selectNumberRule(Calendar.DAY_OF_MONTH, tokenLen);
				break;
			case 'h': // hour in am/pm (number, 1..12)
				rule = new TwelveHourField(selectNumberRule(Calendar.HOUR, tokenLen));
				break;
			case 'H': // hour in day (number, 0..23)
				rule = selectNumberRule(Calendar.HOUR_OF_DAY, tokenLen);
				break;
			case 'm': // minute in hour (number)
				rule = selectNumberRule(Calendar.MINUTE, tokenLen);
				break;
			case 's': // second in minute (number)
				rule = selectNumberRule(Calendar.SECOND, tokenLen);
				break;
			case 'S': // millisecond (number)
				rule = selectNumberRule(Calendar.MILLISECOND, tokenLen);
				break;
			case 'E': // day in week (text)
				rule = new TextField(Calendar.DAY_OF_WEEK, tokenLen < 4 ? shortWeekdays : weekdays);
				break;
			case 'D': // day in year (number)
				rule = selectNumberRule(Calendar.DAY_OF_YEAR, tokenLen);
				break;
			case 'F': // day of week in month (number)
				rule = selectNumberRule(Calendar.DAY_OF_WEEK_IN_MONTH, tokenLen);
				break;
			case 'w': // week in year (number)
				rule = selectNumberRule(Calendar.WEEK_OF_YEAR, tokenLen);
				break;
			case 'W': // week in month (number)
				rule = selectNumberRule(Calendar.WEEK_OF_MONTH, tokenLen);
				break;
			case 'a': // am/pm marker (text)
				rule = new TextField(Calendar.AM_PM, AmPmStrings);
				break;
			case 'k': // hour in day (1..24)
				rule = new TwentyFourHourField(selectNumberRule(Calendar.HOUR_OF_DAY, tokenLen));
				break;
			case 'K': // hour in am/pm (0..11)
				rule = selectNumberRule(Calendar.HOUR, tokenLen);
				break;
			case 'z': // time zone (text)
				if (tokenLen >= 4) {
					rule = new TimeZoneNameRule(mTimeZone, mTimeZoneForced, mLocale, TimeZone.LONG);
				} else {
					rule = new TimeZoneNameRule(mTimeZone, mTimeZoneForced, mLocale, TimeZone.SHORT);
				}
				break;
			case 'Z': // time zone (value)
				if (tokenLen == 1) {
					rule = TimeZoneNumberRule.INSTANCE_NO_COLON;
				} else {
					rule = TimeZoneNumberRule.INSTANCE_COLON;
				}
				break;
			case '\'': // literal text
				final String sub = token.substring(1);
				if (sub.length() == 1) {
					rule = new CharacterLiteral(sub.charAt(0));
				} else {
					rule = new StringLiteral(sub);
				}
				break;
			default:
				throw new IllegalArgumentException("Illegal pattern component: " + token);
			}

			rules.add(rule);
		}

		return rules;
	}

	/**
	 * <p>
	 * Performs the parsing of tokens.
	 * </p>
	 * 
	 * @param pattern
	 *            the pattern
	 * @param indexRef
	 *            index references
	 * @return parsed token
	 */
	protected String parseToken(final String pattern, final int[] indexRef) {
		final StringBuilder buf = new StringBuilder();

		int i = indexRef[0];
		final int length = pattern.length();

		char c = pattern.charAt(i);
		if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
			// Scan a run of the same character, which indicates a time
			// pattern.
			buf.append(c);

			while (i + 1 < length) {
				final char peek = pattern.charAt(i + 1);
				if (peek == c) {
					buf.append(c);
					i++;
				} else {
					break;
				}
			}
		} else {
			// This will identify token as text.
			buf.append('\'');

			boolean inLiteral = false;

			for (; i < length; i++) {
				c = pattern.charAt(i);

				if (c == '\'') {
					if (i + 1 < length && pattern.charAt(i + 1) == '\'') {
						// '' is treated as escaped '
						i++;
						buf.append(c);
					} else {
						inLiteral = !inLiteral;
					}
				} else if (!inLiteral && (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
					i--;
					break;
				} else {
					buf.append(c);
				}
			}
		}

		indexRef[0] = i;
		return buf.toString();
	}

	/**
	 * <p>
	 * Gets an appropriate rule for the padding required.
	 * </p>
	 * 
	 * @param field
	 *            the field to get a rule for
	 * @param padding
	 *            the padding required
	 * @return a new rule with the correct padding
	 */
	protected NumberRule selectNumberRule(final int field, final int padding) {
		switch (padding) {
		case 1:
			return new UnpaddedNumberField(field);
		case 2:
			return new TwoDigitNumberField(field);
		default:
			return new PaddedNumberField(field, padding);
		}
	}

	// Format methods
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Formats a <code>Date</code>, <code>Calendar</code> or <code>Long</code>
	 * (milliseconds) object.
	 * </p>
	 * 
	 * @param obj
	 *            the object to format
	 * @param toAppendTo
	 *            the buffer to append to
	 * @param pos
	 *            the position - ignored
	 * @return the buffer passed in
	 */
	@Override
	public StringBuffer format(final Object obj, final StringBuffer toAppendTo, final FieldPosition pos) {
		if (obj instanceof Date)
			return format((Date) obj, toAppendTo);
		else if (obj instanceof Calendar)
			return format((Calendar) obj, toAppendTo);
		else if (obj instanceof Long)
			return format(((Long) obj).longValue(), toAppendTo);
		else
			throw new IllegalArgumentException("Unknown class: " + (obj == null ? "<null>" : obj.getClass().getName()));
	}

	/**
	 * <p>
	 * Formats a millisecond <code>long</code> value.
	 * </p>
	 * 
	 * @param millis
	 *            the millisecond value to format
	 * @return the formatted string
	 * @since 2.1
	 */
	public String format(final long millis) {
		return format(new Date(millis));
	}

	/**
	 * <p>
	 * Formats a <code>Date</code> object.
	 * </p>
	 * 
	 * @param date
	 *            the date to format
	 * @return the formatted string
	 */
	public String format(final Date date) {
		final Calendar c = new GregorianCalendar(mTimeZone, mLocale);
		c.setTime(date);
		return applyRules(c, new StringBuffer(mMaxLengthEstimate)).toString();
	}

	/**
	 * <p>
	 * Formats a <code>Calendar</code> object.
	 * </p>
	 * 
	 * @param calendar
	 *            the calendar to format
	 * @return the formatted string
	 */
	public String format(final Calendar calendar) {
		return format(calendar, new StringBuffer(mMaxLengthEstimate)).toString();
	}

	/**
	 * <p>
	 * Formats a milliseond <code>long</code> value into the supplied
	 * <code>StringBuffer</code>.
	 * </p>
	 * 
	 * @param millis
	 *            the millisecond value to format
	 * @param buf
	 *            the buffer to format into
	 * @return the specified string buffer
	 * @since 2.1
	 */
	public StringBuffer format(final long millis, final StringBuffer buf) {
		return format(new Date(millis), buf);
	}

	/**
	 * <p>
	 * Formats a <code>Date</code> object into the supplied
	 * <code>StringBuffer</code>.
	 * </p>
	 * 
	 * @param date
	 *            the date to format
	 * @param buf
	 *            the buffer to format into
	 * @return the specified string buffer
	 */
	public StringBuffer format(final Date date, final StringBuffer buf) {
		final Calendar c = new GregorianCalendar(mTimeZone);
		c.setTime(date);
		return applyRules(c, buf);
	}

	/**
	 * <p>
	 * Formats a <code>Calendar</code> object into the supplied
	 * <code>StringBuffer</code>.
	 * </p>
	 * 
	 * @param calendar
	 *            the calendar to format
	 * @param buf
	 *            the buffer to format into
	 * @return the specified string buffer
	 */
	public StringBuffer format(Calendar calendar, final StringBuffer buf) {
		if (mTimeZoneForced) {
			calendar.getTimeInMillis(); // / LANG-538
			calendar = (Calendar) calendar.clone();
			calendar.setTimeZone(mTimeZone);
		}
		return applyRules(calendar, buf);
	}

	/**
	 * <p>
	 * Performs the formatting by applying the rules to the specified calendar.
	 * </p>
	 * 
	 * @param calendar
	 *            the calendar to format
	 * @param buf
	 *            the buffer to format into
	 * @return the specified string buffer
	 */
	protected StringBuffer applyRules(final Calendar calendar, final StringBuffer buf) {
		final Rule[] rules = mRules;
		final int len = mRules.length;
		for (int i = 0; i < len; i++) {
			rules[i].appendTo(buf, calendar);
		}
		return buf;
	}

	// Parsing
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Parsing is not supported.
	 * </p>
	 * 
	 * @param source
	 *            the string to parse
	 * @param pos
	 *            the parsing position
	 * @return <code>null</code> as not supported
	 */
	@Override
	public Object parseObject(final String source, final ParsePosition pos) {
		pos.setIndex(0);
		pos.setErrorIndex(0);
		return null;
	}

	// Accessors
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Gets the pattern used by this formatter.
	 * </p>
	 * 
	 * @return the pattern, {@link java.text.SimpleDateFormat} compatible
	 */
	public String getPattern() {
		return mPattern;
	}

	/**
	 * <p>
	 * Gets the time zone used by this formatter.
	 * </p>
	 * 
	 * <p>
	 * This zone is always used for <code>Date</code> formatting. If a
	 * <code>Calendar</code> is passed in to be formatted, the time zone on that
	 * may be used depending on {@link #getTimeZoneOverridesCalendar()}.
	 * </p>
	 * 
	 * @return the time zone
	 */
	public TimeZone getTimeZone() {
		return mTimeZone;
	}

	/**
	 * <p>
	 * Returns <code>true</code> if the time zone of the calendar overrides the
	 * time zone of the formatter.
	 * </p>
	 * 
	 * @return <code>true</code> if time zone of formatter overridden for
	 *         calendars
	 */
	public boolean getTimeZoneOverridesCalendar() {
		return mTimeZoneForced;
	}

	/**
	 * <p>
	 * Gets the locale used by this formatter.
	 * </p>
	 * 
	 * @return the locale
	 */
	public Locale getLocale() {
		return mLocale;
	}

	/**
	 * <p>
	 * Gets an estimate for the maximum string length that the formatter will
	 * produce.
	 * </p>
	 * 
	 * <p>
	 * The actual formatted length will almost always be less than or equal to
	 * this amount.
	 * </p>
	 * 
	 * @return the maximum formatted length
	 */
	public int getMaxLengthEstimate() {
		return mMaxLengthEstimate;
	}

	// Basics
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Compares two objects for equality.
	 * </p>
	 * 
	 * @param obj
	 *            the object to compare to
	 * @return <code>true</code> if equal
	 */
	@Override
	public boolean equals(final Object obj) {
		if (obj instanceof FastDateFormat == false)
			return false;
		final FastDateFormat other = (FastDateFormat) obj;
		if ((mPattern == other.mPattern || mPattern.equals(other.mPattern)) && (mTimeZone == other.mTimeZone || mTimeZone.equals(other.mTimeZone))
				&& (mLocale == other.mLocale || mLocale.equals(other.mLocale)) && mTimeZoneForced == other.mTimeZoneForced
				&& mLocaleForced == other.mLocaleForced)
			return true;
		return false;
	}

	/**
	 * <p>
	 * Returns a hashcode compatible with equals.
	 * </p>
	 * 
	 * @return a hashcode compatible with equals
	 */
	@Override
	public int hashCode() {
		int total = 0;
		total += mPattern.hashCode();
		total += mTimeZone.hashCode();
		total += mTimeZoneForced ? 1 : 0;
		total += mLocale.hashCode();
		total += mLocaleForced ? 1 : 0;
		return total;
	}

	/**
	 * <p>
	 * Gets a debugging string version of this formatter.
	 * </p>
	 * 
	 * @return a debugging string
	 */
	@Override
	public String toString() {
		return "FastDateFormat[" + mPattern + "]";
	}

	// Serializing
	// -----------------------------------------------------------------------
	/**
	 * Create the object after serialization. This implementation reinitializes
	 * the transient properties.
	 * 
	 * @param in
	 *            ObjectInputStream from which the object is being deserialized.
	 * @throws IOException
	 *             if there is an IO issue.
	 * @throws ClassNotFoundException
	 *             if a class cannot be found.
	 */
	private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		init();
	}

	// Rules
	// -----------------------------------------------------------------------
	/**
	 * <p>
	 * Inner class defining a rule.
	 * </p>
	 */
	private interface Rule {
		/**
		 * Returns the estimated lentgh of the result.
		 * 
		 * @return the estimated length
		 */
		int estimateLength();

		/**
		 * Appends the value of the specified calendar to the output buffer
		 * based on the rule implementation.
		 * 
		 * @param buffer
		 *            the output buffer
		 * @param calendar
		 *            calendar to be appended
		 */
		void appendTo(StringBuffer buffer, Calendar calendar);
	}

	/**
	 * <p>
	 * Inner class defining a numeric rule.
	 * </p>
	 */
	private interface NumberRule extends Rule {
		/**
		 * Appends the specified value to the output buffer based on the rule
		 * implementation.
		 * 
		 * @param buffer
		 *            the output buffer
		 * @param value
		 *            the value to be appended
		 */
		void appendTo(StringBuffer buffer, int value);
	}

	/**
	 * <p>
	 * Inner class to output a constant single character.
	 * </p>
	 */
	private static class CharacterLiteral implements Rule {
		private final char mValue;

		/**
		 * Constructs a new instance of <code>CharacterLiteral</code> to hold
		 * the specified value.
		 * 
		 * @param value
		 *            the character literal
		 */
		CharacterLiteral(final char value) {
			mValue = value;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 1;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			buffer.append(mValue);
		}
	}

	/**
	 * <p>
	 * Inner class to output a constant string.
	 * </p>
	 */
	private static class StringLiteral implements Rule {
		private final String mValue;

		/**
		 * Constructs a new instance of <code>StringLiteral</code> to hold the
		 * specified value.
		 * 
		 * @param value
		 *            the string literal
		 */
		StringLiteral(final String value) {
			mValue = value;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return mValue.length();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			buffer.append(mValue);
		}
	}

	/**
	 * <p>
	 * Inner class to output one of a set of values.
	 * </p>
	 */
	private static class TextField implements Rule {
		private final int mField;
		private final String[] mValues;

		/**
		 * Constructs an instance of <code>TextField</code> with the specified
		 * field and values.
		 * 
		 * @param field
		 *            the field
		 * @param values
		 *            the field values
		 */
		TextField(final int field, final String[] values) {
			mField = field;
			mValues = values;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			int max = 0;
			for (int i = mValues.length; --i >= 0;) {
				final int len = mValues[i].length();
				if (len > max) {
					max = len;
				}
			}
			return max;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			buffer.append(mValues[calendar.get(mField)]);
		}
	}

	/**
	 * <p>
	 * Inner class to output an unpadded number.
	 * </p>
	 */
	private static class UnpaddedNumberField implements NumberRule {
		private final int mField;

		/**
		 * Constructs an instance of <code>UnpadedNumberField</code> with the
		 * specified field.
		 * 
		 * @param field
		 *            the field
		 */
		UnpaddedNumberField(final int field) {
			mField = field;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 4;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(mField));
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			if (value < 10) {
				buffer.append((char) (value + '0'));
			} else if (value < 100) {
				buffer.append((char) (value / 10 + '0'));
				buffer.append((char) (value % 10 + '0'));
			} else {
				buffer.append(Integer.toString(value));
			}
		}
	}

	/**
	 * <p>
	 * Inner class to output an unpadded month.
	 * </p>
	 */
	private static class UnpaddedMonthField implements NumberRule {
		static final UnpaddedMonthField INSTANCE = new UnpaddedMonthField();

		/**
		 * Constructs an instance of <code>UnpaddedMonthField</code>.
		 * 
		 */
		UnpaddedMonthField() {
			super();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 2;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(Calendar.MONTH) + 1);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			if (value < 10) {
				buffer.append((char) (value + '0'));
			} else {
				buffer.append((char) (value / 10 + '0'));
				buffer.append((char) (value % 10 + '0'));
			}
		}
	}

	/**
	 * <p>
	 * Inner class to output a padded number.
	 * </p>
	 */
	private static class PaddedNumberField implements NumberRule {
		private final int mField;
		private final int mSize;

		/**
		 * Constructs an instance of <code>PaddedNumberField</code>.
		 * 
		 * @param field
		 *            the field
		 * @param size
		 *            size of the output field
		 */
		PaddedNumberField(final int field, final int size) {
			if (size < 3)
				// Should use UnpaddedNumberField or TwoDigitNumberField.
				throw new IllegalArgumentException();
			mField = field;
			mSize = size;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 4;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(mField));
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			if (value < 100) {
				for (int i = mSize; --i >= 2;) {
					buffer.append('0');
				}
				buffer.append((char) (value / 10 + '0'));
				buffer.append((char) (value % 10 + '0'));
			} else {
				int digits;
				if (value < 1000) {
					digits = 3;
				} else {
					if (value < 0)
						throw new IllegalArgumentException(String.format("Negative values should not be possible: %d", value));
					digits = Integer.toString(value).length();
				}
				for (int i = mSize; --i >= digits;) {
					buffer.append('0');
				}
				buffer.append(Integer.toString(value));
			}
		}
	}

	/**
	 * <p>
	 * Inner class to output a two digit number.
	 * </p>
	 */
	private static class TwoDigitNumberField implements NumberRule {
		private final int mField;

		/**
		 * Constructs an instance of <code>TwoDigitNumberField</code> with the
		 * specified field.
		 * 
		 * @param field
		 *            the field
		 */
		TwoDigitNumberField(final int field) {
			mField = field;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 2;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(mField));
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			if (value < 100) {
				buffer.append((char) (value / 10 + '0'));
				buffer.append((char) (value % 10 + '0'));
			} else {
				buffer.append(Integer.toString(value));
			}
		}
	}

	/**
	 * <p>
	 * Inner class to output a two digit year.
	 * </p>
	 */
	private static class TwoDigitYearField implements NumberRule {
		static final TwoDigitYearField INSTANCE = new TwoDigitYearField();

		/**
		 * Constructs an instance of <code>TwoDigitYearField</code>.
		 */
		TwoDigitYearField() {
			super();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 2;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(Calendar.YEAR) % 100);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			buffer.append((char) (value / 10 + '0'));
			buffer.append((char) (value % 10 + '0'));
		}
	}

	/**
	 * <p>
	 * Inner class to output a two digit month.
	 * </p>
	 */
	private static class TwoDigitMonthField implements NumberRule {
		static final TwoDigitMonthField INSTANCE = new TwoDigitMonthField();

		/**
		 * Constructs an instance of <code>TwoDigitMonthField</code>.
		 */
		TwoDigitMonthField() {
			super();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 2;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			appendTo(buffer, calendar.get(Calendar.MONTH) + 1);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public final void appendTo(final StringBuffer buffer, final int value) {
			buffer.append((char) (value / 10 + '0'));
			buffer.append((char) (value % 10 + '0'));
		}
	}

	/**
	 * <p>
	 * Inner class to output the twelve hour field.
	 * </p>
	 */
	private static class TwelveHourField implements NumberRule {
		private final NumberRule mRule;

		/**
		 * Constructs an instance of <code>TwelveHourField</code> with the
		 * specified <code>NumberRule</code>.
		 * 
		 * @param rule
		 *            the rule
		 */
		TwelveHourField(final NumberRule rule) {
			mRule = rule;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return mRule.estimateLength();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			int value = calendar.get(Calendar.HOUR);
			if (value == 0) {
				value = calendar.getLeastMaximum(Calendar.HOUR) + 1;
			}
			mRule.appendTo(buffer, value);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final int value) {
			mRule.appendTo(buffer, value);
		}
	}

	/**
	 * <p>
	 * Inner class to output the twenty four hour field.
	 * </p>
	 */
	private static class TwentyFourHourField implements NumberRule {
		private final NumberRule mRule;

		/**
		 * Constructs an instance of <code>TwentyFourHourField</code> with the
		 * specified <code>NumberRule</code>.
		 * 
		 * @param rule
		 *            the rule
		 */
		TwentyFourHourField(final NumberRule rule) {
			mRule = rule;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return mRule.estimateLength();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			int value = calendar.get(Calendar.HOUR_OF_DAY);
			if (value == 0) {
				value = calendar.getMaximum(Calendar.HOUR_OF_DAY) + 1;
			}
			mRule.appendTo(buffer, value);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final int value) {
			mRule.appendTo(buffer, value);
		}
	}

	/**
	 * <p>
	 * Inner class to output a time zone name.
	 * </p>
	 */
	private static class TimeZoneNameRule implements Rule {
		private final TimeZone mTimeZone;
		private final boolean mTimeZoneForced;
		private final Locale mLocale;
		private final int mStyle;
		private final String mStandard;
		private final String mDaylight;

		/**
		 * Constructs an instance of <code>TimeZoneNameRule</code> with the
		 * specified properties.
		 * 
		 * @param timeZone
		 *            the time zone
		 * @param timeZoneForced
		 *            if <code>true</code> the time zone is forced into standard
		 *            and daylight
		 * @param locale
		 *            the locale
		 * @param style
		 *            the style
		 */
		TimeZoneNameRule(final TimeZone timeZone, final boolean timeZoneForced, final Locale locale, final int style) {
			mTimeZone = timeZone;
			mTimeZoneForced = timeZoneForced;
			mLocale = locale;
			mStyle = style;

			if (timeZoneForced) {
				mStandard = getTimeZoneDisplay(timeZone, false, style, locale);
				mDaylight = getTimeZoneDisplay(timeZone, true, style, locale);
			} else {
				mStandard = null;
				mDaylight = null;
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			if (mTimeZoneForced)
				return Math.max(mStandard.length(), mDaylight.length());
			else if (mStyle == TimeZone.SHORT)
				return 4;
			else
				return 40;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			if (mTimeZoneForced) {
				if (mTimeZone.useDaylightTime() && calendar.get(Calendar.DST_OFFSET) != 0) {
					buffer.append(mDaylight);
				} else {
					buffer.append(mStandard);
				}
			} else {
				final TimeZone timeZone = calendar.getTimeZone();
				if (timeZone.useDaylightTime() && calendar.get(Calendar.DST_OFFSET) != 0) {
					buffer.append(getTimeZoneDisplay(timeZone, true, mStyle, mLocale));
				} else {
					buffer.append(getTimeZoneDisplay(timeZone, false, mStyle, mLocale));
				}
			}
		}
	}

	/**
	 * <p>
	 * Inner class to output a time zone as a number <code>+/-HHMM</code> or
	 * <code>+/-HH:MM</code>.
	 * </p>
	 */
	private static class TimeZoneNumberRule implements Rule {
		static final TimeZoneNumberRule INSTANCE_COLON = new TimeZoneNumberRule(true);
		static final TimeZoneNumberRule INSTANCE_NO_COLON = new TimeZoneNumberRule(false);

		final boolean mColon;

		/**
		 * Constructs an instance of <code>TimeZoneNumberRule</code> with the
		 * specified properties.
		 * 
		 * @param colon
		 *            add colon between HH and MM in the output if
		 *            <code>true</code>
		 */
		TimeZoneNumberRule(final boolean colon) {
			mColon = colon;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int estimateLength() {
			return 5;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void appendTo(final StringBuffer buffer, final Calendar calendar) {
			int offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET);

			if (offset < 0) {
				buffer.append('-');
				offset = -offset;
			} else {
				buffer.append('+');
			}

			final int hours = offset / (60 * 60 * 1000);
			buffer.append((char) (hours / 10 + '0'));
			buffer.append((char) (hours % 10 + '0'));

			if (mColon) {
				buffer.append(':');
			}

			final int minutes = offset / (60 * 1000) - 60 * hours;
			buffer.append((char) (minutes / 10 + '0'));
			buffer.append((char) (minutes % 10 + '0'));
		}
	}

	// ----------------------------------------------------------------------
	/**
	 * <p>
	 * Inner class that acts as a compound key for time zone names.
	 * </p>
	 */
	private static class TimeZoneDisplayKey {
		private final TimeZone mTimeZone;
		private final int mStyle;
		private final Locale mLocale;

		/**
		 * Constructs an instance of <code>TimeZoneDisplayKey</code> with the
		 * specified properties.
		 * 
		 * @param timeZone
		 *            the time zone
		 * @param daylight
		 *            adjust the style for daylight saving time if
		 *            <code>true</code>
		 * @param style
		 *            the timezone style
		 * @param locale
		 *            the timezone locale
		 */
		TimeZoneDisplayKey(final TimeZone timeZone, final boolean daylight, int style, final Locale locale) {
			mTimeZone = timeZone;
			if (daylight) {
				style |= 0x80000000;
			}
			mStyle = style;
			mLocale = locale;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int hashCode() {
			return mStyle * 31 + mLocale.hashCode();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean equals(final Object obj) {
			if (this == obj)
				return true;
			if (obj instanceof TimeZoneDisplayKey) {
				final TimeZoneDisplayKey other = (TimeZoneDisplayKey) obj;
				return mTimeZone.equals(other.mTimeZone) && mStyle == other.mStyle && mLocale.equals(other.mLocale);
			}
			return false;
		}
	}

	// ----------------------------------------------------------------------
	/**
	 * <p>
	 * Helper class for creating compound objects.
	 * </p>
	 * 
	 * <p>
	 * One use for this class is to create a hashtable key out of multiple
	 * objects.
	 * </p>
	 */
	private static class Pair {
		private final Object mObj1;
		private final Object mObj2;

		/**
		 * Constructs an instance of <code>Pair</code> to hold the specified
		 * objects.
		 * 
		 * @param obj1
		 *            one object in the pair
		 * @param obj2
		 *            second object in the pair
		 */
		public Pair(final Object obj1, final Object obj2) {
			mObj1 = obj1;
			mObj2 = obj2;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean equals(final Object obj) {
			if (this == obj)
				return true;

			if (!(obj instanceof Pair))
				return false;

			final Pair key = (Pair) obj;

			return (mObj1 == null ? key.mObj1 == null : mObj1.equals(key.mObj1)) && (mObj2 == null ? key.mObj2 == null : mObj2.equals(key.mObj2));
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int hashCode() {
			return (mObj1 == null ? 0 : mObj1.hashCode()) + (mObj2 == null ? 0 : mObj2.hashCode());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String toString() {
			return "[" + mObj1 + ':' + mObj2 + ']';
		}
	}

}