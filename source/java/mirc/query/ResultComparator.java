/*---------------------------------------------------------------
*  Copyright 2010 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package mirc.query;

import java.util.*;
import org.rsna.util.*;
import org.w3c.dom.*;


/**
 * A Comparator for sorting query results objects.
 */
public class ResultComparator implements Comparator {

	static final String[] keynames = {
		"title",
		"library",
		"author",
		"specialty",
		"pubdate",
		"lmdate"
	};

	int key = keynames.length - 1;

	/**
	 * Create a reverse order Comparator for lmDate values.
	 */
	public ResultComparator(String primaryKey) {
		for (int i=0; i<keynames.length; i++) {
			if (primaryKey.equals(keynames[i])) {
				key = i;
				break;
			}
		}
	}

	/**
	 * Compare.
	 */
	public int compare(Object o1, Object o2) {
		if ( (o1 instanceof Element) && (o2 instanceof Element)) {

			Element eo1 = (Element)o1;
			Element eo2 = (Element)o2;

			Element primary1;
			Element primary2;
			Element secondary1;
			Element secondary2;

			int c;

			switch (key) {

				case 0:
					//primary: title; secondary: lmdate
					primary2 = XmlUtil.getFirstNamedChild(eo2, "title");
					if (primary2 == null) return 1;
					primary1 = XmlUtil.getFirstNamedChild(eo1, "title");
					if (primary1 == null) return -1;
					c = primary1.getTextContent().compareToIgnoreCase(primary2.getTextContent());
					if (c != 0) return c;
					return compareDates("lmdate", eo1, eo2);

				case 1:
					//primary: library; secondary: lmdate
					primary2 = XmlUtil.getFirstNamedChild(eo2, "server");
					if (primary2 == null) return 1;
					primary1 = XmlUtil.getFirstNamedChild(eo1, "server");
					if (primary1 == null) return -1;
					c = primary1.getTextContent().compareToIgnoreCase(primary2.getTextContent());
					if (c != 0) return c;
					return compareDates("lmdate", eo1, eo2);

				case 2:
					//primary: library; secondary: lmdate
					primary2 = XmlUtil.getFirstNamedChild(eo2, "author");
					if (primary2 == null) return 1;
					primary1 = XmlUtil.getFirstNamedChild(eo1, "author");
					if (primary1 == null) return -1;
					//get the name children
					primary2 = XmlUtil.getFirstNamedChild(primary2, "name");
					if (primary2 == null) return -1;
					primary1 = XmlUtil.getFirstNamedChild(primary1, "name");
					if (primary1 == null) return 1;
					c = primary1.getTextContent().compareToIgnoreCase(primary2.getTextContent());
					if (c != 0) return c;
					return compareDates("lmdate", eo1, eo2);

				case 3:
					//primary: specialty; secondary: lmdate
					primary2 = XmlUtil.getFirstNamedChild(eo2, "category");
					if (primary2 == null) return 1;
					primary1 = XmlUtil.getFirstNamedChild(eo1, "category");
					if (primary1 == null) return -1;
					c = primary1.getTextContent().compareToIgnoreCase(primary2.getTextContent());
					if (c != 0) return c;
					return compareDates("lmdate", eo1, eo2);

				case 4:
					//primary: pubdate
					return compareDates("pubdate", eo1, eo2);

				default:
					//primary: lmdate
					return compareDates("lmdate", eo1, eo2);
			}
		}
		else return 0;
	}

	private int compareDates(String datename, Element eo1, Element eo2) {
		Element date2 = XmlUtil.getFirstNamedChild(eo2, datename);
		if (date2 == null) return -1;
		Element date1 = XmlUtil.getFirstNamedChild(eo1, datename);
		if (date1 == null) return 1;
		return -date1.getTextContent().compareTo(date2.getTextContent());
	}

}