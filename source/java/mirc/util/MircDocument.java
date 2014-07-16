/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package mirc.util;

import java.awt.Dimension;
import java.io.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import org.apache.log4j.Logger;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import mirc.MircConfig;
import mirc.storage.Index;
import mirc.util.MircImage;
import mirc.util.RadLexIndex;
import mirc.util.Term;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.objects.ZipObject;
import org.rsna.ctp.stdstages.anonymizer.dicom.DAScript;
import org.rsna.ctp.stdstages.anonymizer.dicom.DICOMAnonymizer;
import org.rsna.ctp.stdstages.anonymizer.IntegerTable;
import org.rsna.ctp.stdstages.anonymizer.LookupTable;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.server.User;
import org.rsna.util.DateUtil;
import org.rsna.util.DigestUtil;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.XmlUtil;

import org.rsna.video.AVIOutputStream;
//import org.jcodec.api.SequenceEncoder;

/**
  * A class to encapsulate a MIRCdocument.
  */
public class MircDocument {

	static final Logger logger = Logger.getLogger(MircDocument.class);

	static final SkipElements skip = new SkipElements();

	Document doc;
	File docDir;
	File docFile;
	int jpegQuality = -1;

	/**
	 * Class constructor; creates a new MircDocument object from a File.
	 * If the referenced file exists, it is loaded.
	 * @param docFile pointing to the MircDocument to be loaded.
	 * @throws Exception if the file cannot be found, or if the
	 * file does not parse, or if the document is not a MIRCdocument.
	 */
	public MircDocument(File docFile) throws Exception {
		this.docFile = new File( docFile.getAbsolutePath() );
		if (!docFile.exists()) throw new Exception(docFile + " could not be found.");
		docDir = docFile.getParentFile();
		doc = XmlUtil.getDocument(docFile);
		if (!doc.getDocumentElement().getTagName().equals("MIRCdocument")) {
			throw new Exception("Not a MIRCdocument.");
		}
	}

	/**
	 * Class constructor; creates a new MircDocument object from an XML Document.
	 * Note that this constructor does not include a File, so the save method is
	 * disabled until the saveAs or setFile method is called.
	 * @param doc the MIRCdocument XML Document.
	 * @throws Exception if the XML Document is not a MIRCdocument.
	 */
	public MircDocument(Document doc) throws Exception {
		this.doc = doc;
		if (!doc.getDocumentElement().getTagName().equals("MIRCdocument")) {
			throw new Exception("Not a MIRCdocument.");
		}
		docDir = null;
		docFile = null;
	}

	/**
	 * Class constructor; creates a new MircDocument object from an XML text string
	 * Note that this constructor does not include a File, so the save method is
	 * disabled until the saveAs or setFile method is called.
	 * @param docText the text of the MIRCdocument.
	 * @throws Exception if the XML does not parse or the document is not a MIRCdocument.
	 */
	public MircDocument(String docText) throws Exception {
		this.doc = XmlUtil.getDocument( docText );
		if (!doc.getDocumentElement().getTagName().equals("MIRCdocument")) {
			throw new Exception("Not a MIRCdocument.");
		}
		docDir = null;
		docFile = null;
	}

	/**
	 * Set the file without writing the text of the MIRCdocument.
	 */
	public void setFile(File file) {
		this.docFile = new File( file.getAbsolutePath() );
		this.docDir = this.docFile.getParentFile();
	}

	/**
	 * Get the directory.
	 * @return the directory associated with this MIRCdocument.
	 * Note that this can be null if the MIRCdocument was created from an XML
	 * Document rather than a File and the MIRCdocument has not yet been saved.
	 */
	public File getDirectory() {
		return docDir;
	}

	/**
	 * Get the file.
	 * @return the file associated with this MIRCdocument.
	 * Note that this can be null if the MIRCdocument was created from an XML
	 * Document rather than a File and the MIRCdocument has not yet been saved.
	 */
	public File getFile() {
		return docFile;
	}

	/**
	 * Get the XML Document.
	 * @return the XML Document associated with this MIRCdocument.
	 */
	public Document getXML() {
		return doc;
	}

	/**
	 * Set the file and save the document text.
	 * @param file the file in which to save the document. This
	 * becomes the file for all subsequent save() method calls as well.
	 * @return true if the document was saved and indexed; false otherwise.
	 */
	public boolean saveAs(File file) {
		setFile( file );
		return save();
	}

	/**
	 * Save the document text.
	 * @return true if the document was saved and indexed; false otherwise.
	 */
	public boolean save() {
		if (docFile != null) {
			return FileUtil.setText( docFile, XmlUtil.toString(doc) );
		}
		else return false;
	}

	/**
	 * Construct a File to contain a MIRCdocument zip export. The filename
	 * consists of the name of the parent directory, an underscore, and the
	 * text content of the MIRCdocument's title element (modified to make an
	 * acceptable filename, with whitespace, slashes, backslashes, and ampersands
	 * replaced by underscores). The extension is ".zip" unless the extParameter
	 * is supplied as non-null and non-empty, in which case the extension is
	 * "." + extParameter.
	 * @param doc the MIRCdocument XML Document object.
	 * @param file the file containing the MIRCdocument.
	 * @param extParameter the desired extension (with no leading period). If this parameter
	 * is null or empty, the default extension ".zip" is supplied.
	 * @return the file (note that this is only a File object; this method does not
	 * insert the zip contents).
	 */
	public static File getFileForZip(Document doc, File file, String extParameter) {
		String ext = ".zip";
		if ((extParameter != null) && !extParameter.equals("")) ext = "." + extParameter;
		File parent = file.getParentFile();
		String name = makeNameFromParent(file);
		Element titleEl = XmlUtil.getFirstNamedChild(doc, "title");
		if (titleEl != null) {
			String title = titleEl.getTextContent().trim();
			title = title.replaceAll("\\s+", "_");
			title = title.replaceAll("/", "-");
			title = title.replaceAll("\\\\", "-");
			title = title.replaceAll(":", "-");
			if (title.length() > 0) name = title + "_" + name;
		}
		name += ext;
		return new File(parent, name);
	}

	private static String makeNameFromParent(File file) {
		file = new File(file.getAbsolutePath());
		File parent = file.getParentFile();
		String name = parent.getName();
		while ((name.length() < 10) && ( (parent=parent.getParentFile()) != null )) {
			name = parent.getName() + "_" + name;
		}
		return name;
	}

	/**
	 * Create an OpenOffice presentation file for this MircDocument;
	 * @return a file in the temp directory containing an OpenOffice
	 * presentation (ODP) file
	 * @throws Exception if any error occurs..
	 */
	public File getPresentation(boolean userIsOwner) throws Exception {
		//Make a directory in which to play
		File dir = FileUtil.createTempDirectory(docDir);

		//Get a file for the presentation (in the root directory of the MIRCdocument)
		File odpFile = getFileForZip(doc, docFile, "odp");

		//Find all the images, put them into the pictures directory,
		//and create the XML document that lists them
		File pictures = new File(dir, "Pictures");
		pictures.mkdirs();
		Document imagesDoc = XmlUtil.getDocument();
		Element images = imagesDoc.createElement("images");
		imagesDoc.appendChild(images);
		NodeList nl = doc.getDocumentElement().getElementsByTagName("image");
		for (int i=0; i<nl.getLength(); i++) {
			Element img = (Element)nl.item(i);
			String name = img.getAttribute("src");
			if (!name.startsWith("/") && !name.toLowerCase().startsWith("http://")) {

				//Check whether there is an original-dimensions version
				NodeList alt = img.getElementsByTagName("alternative-image");
				for (int k=0; k<alt.getLength(); k++) {
					Element altimg = (Element)alt.item(k);
					if (altimg.getAttribute("role").equals("original-dimensions")) {
						String altsrc = altimg.getAttribute("src").toLowerCase();
						if (altsrc.endsWith(".jpg") || altsrc.endsWith("jpeg")) {
							img = altimg;
							break;
						}
					}
				}
				appendImg(images, img, name, pictures);

				//Check whether there is an annotated image.
				for (int k=0; k<alt.getLength(); k++) {
					Element altimg = (Element)alt.item(k);
					if (altimg.getAttribute("role").equals("annotation")) {
						String altsrc = altimg.getAttribute("src").toLowerCase();
						if (altsrc.endsWith(".jpg") || altsrc.endsWith("jpeg")) {
							String altname = altimg.getAttribute("src");
							appendImg(images, altimg, altname, pictures);
							break;
						}
					}
				}
			}
		}

		//Add in the little png that OO needs for one of its styles
		String pngName = "10000000000000200000002000309F1C.png";
		File png = new File(pictures, pngName);
		FileUtil.getFile(png, "/odp/"+pngName);

		//Copy in the styles
		FileUtil.getFile(new File(dir, "styles.xml"), "/odp/styles.xml");

		//Make the META-INF directory and create the manifest.xml file
		File metaInf = new File(dir, "META-INF");
		metaInf.mkdirs();
		File manifestFile = new File(metaInf, "manifest.xml");
		Document xsl = XmlUtil.getDocument( FileUtil.getStream( "/odp/manifest.xsl" ) );
		Document manifest = XmlUtil.getTransformedDocument( imagesDoc, xsl, null );
		FileUtil.setText(manifestFile, XmlUtil.toString(manifest));

		//Process the Document and create the slides file
		File contentFile = new File(dir, "content.xml");
		xsl = XmlUtil.getDocument( FileUtil.getStream( "/odp/content.xsl" ) );
		Object[] params = {
			"images", imagesDoc,
			"userIsOwner", (userIsOwner ? "yes" : "no")
		};
		Document content = XmlUtil.getTransformedDocument( doc, xsl, params );
		FileUtil.setText(contentFile, XmlUtil.toString(content));

		//Now zip it all up. Note that we suppress the name of the dir.
		//If we didn't, neither OO nor PPT would open the file.
		FileUtil.zipDirectory(dir, odpFile, true);

		//Delete the temp directory and return the file.
		FileUtil.deleteAll(dir);
		return odpFile;
	}

	//Append an image. Constructing the parameters that allow it
	//to be scaled to the slide. Copy the image to the pictures directory.
	private void appendImg(Element images, Element img, String name, File pictures) {
		String src = img.getAttribute("src");
		int w = StringUtil.getInt(img.getAttribute("w"));
		int h = StringUtil.getInt(img.getAttribute("h"));

		if ((w != 0) && (h != 0)) {

			//Now figure out how to place and scale the image on the slide.
			//This has to be a lot easier done in Java than in XSL.
			float slideWidth = 28;
			float slideHeight = 21;
			float marginX = 1;
			float marginY = 1;
			float areaWidth = slideWidth - 2*marginX;
			float areaHeight = slideHeight - 2*marginY;
			float areaAspectRatio = areaHeight / areaWidth;;
			float imageAspectRatio = (float)h / (float)w;

			float xcm, ycm, wcm, hcm;

			if (areaAspectRatio < imageAspectRatio) {
				//fit the image to the height of the area
				float scale = areaHeight / (float)h;
				ycm = marginY;
				hcm = areaHeight;
				wcm = (float)w * scale;
				xcm = marginX + (areaWidth - wcm)/2;
			}
			else {
				//fit the image to the width of the area
				xcm = marginX;
				wcm = areaWidth;
				float scale = areaWidth / (float)w;
				hcm = (float)h * scale;
				ycm = marginY + (areaHeight - hcm)/2;
			}

			//Okay, now create the element
			Element image = images.getOwnerDocument().createElement("image");
			images.appendChild(image);
			image.setAttribute("name", name); //this is the value that indexes the image
			image.setAttribute("src", src); //this is the value that points to the version to use
			image.setAttribute("x", String.format(Locale.US, "%.3fcm", xcm));
			image.setAttribute("y", String.format(Locale.US, "%.3fcm", ycm));
			image.setAttribute("w", String.format(Locale.US, "%.3fcm", wcm));
			image.setAttribute("h", String.format(Locale.US, "%.3fcm", hcm));

			//Now copy the selected image to the Pictures directory
			File inFile = new File(docDir, src);
			File outFile = new File(pictures, src);
			FileUtil.copy(inFile, outFile);
		}
	}

	/**
	 * Set the publication-date element to the current time, creating
	 * the element if it is missing from the document. This method does
	 * not modify the element if it already exists and contains non-whitespace
	 * characters.
	 * @param root the root element of the document
	 */
	public static void setPublicationDate(Element root) {
		setPublicationDate(root, System.currentTimeMillis());
	}

	/**
	 * Set the publication-date element to the specified time, creating
	 * the element if it is missing from the document. This method does
	 * not modify the element if it already exists and contains non-whitespace
	 * characters.
	 * @param root the root element of the document
	 * @param time the time to set in the publication-date element if a time is
	 * not lready available in the element.
	 */
	public static void setPublicationDate(Element root, long time) {
		Element pd = XmlUtil.getFirstNamedChild(root, "publication-date");
		if (pd == null) {
			pd = root.getOwnerDocument().createElement("publication-date");
			root.appendChild(pd);
		}
		String text = pd.getTextContent().trim();
		if (text.equals("")) pd.setTextContent( StringUtil.getDate(time, "-") );
	}

	/**
	 * Set the publication request attribute and adjust the authorization/read
	 * element if the document is public and publication is not authorized.
	 * @param canPublish true if the document is allowed to be public.
	 */
	public void setPublicationRequest(boolean canPublish) {
		if (isPublic()) {
			if (canPublish) {
				clearPublicationRequest();
			}
			else {
				makeNonPublic();
				setPublicationRequest();
			}
		}
		//else leave the permissions and the publication request alone
	}

	/**
	 * Set the publication request.
	 */
	public void setPublicationRequest() {
		Element root = doc.getDocumentElement();
		root.setAttribute("pubreq", "yes");
		//Make sure a publisher can view and edit the document
		Element auth = XmlUtil.getFirstNamedChild(root, "authorization");
		if (auth == null) {
			auth = doc.createElement("authorization");
			root.appendChild(auth);
		}
		Element read = XmlUtil.getFirstNamedChild(auth, "read");
		if (read == null) {
			read = doc.createElement("read");
			auth.appendChild(read);
		}
		if (read.getTextContent().trim().equals("")) {
			read.setTextContent("publisher");
		}
		Element update = XmlUtil.getFirstNamedChild(auth, "update");
		if (update == null) {
			update = doc.createElement("update");
			auth.appendChild(update);
		}
		if (update.getTextContent().trim().equals("")) {
			update.setTextContent("publisher");
		}
	}

	/**
	 * Clear the publication request.
	 */
	public void clearPublicationRequest() {
		doc.getDocumentElement().removeAttribute("pubreq");
	}

	/**
	 * Determine whether the document is public. For compatibility
	 * with previous MIRC versions, a document is publicly visible
	 * if its MIRCdocument/authorization/read element is missing
	 * or if it contains an asterisk.
	 * @return true if the document authorizes access by unauthenticated
	 * users.
	 */
	public boolean isPublic() {
		Element root = doc.getDocumentElement();
		Element auth = XmlUtil.getFirstNamedChild(root, "authorization");
		if (auth == null) return true;
		Element read = XmlUtil.getFirstNamedChild(auth, "read");
		if (read == null) return true;
		return read.getTextContent().contains("*");
	}

	/**
	 * Determine whether the document is a draft. A document is
	 * a draft if its root element has the <code>temp</code> atttibute set to "yes".
	 * @return true if the document is a draft.
	 */
	public boolean isDraft() {
		Element root = doc.getDocumentElement();
		return root.getAttribute("temp").equals("yes");
	}

	/**
	 * Make the document non-public.
	 */
	public void makeNonPublic() {
		Element root = doc.getDocumentElement();
		Element auth = XmlUtil.getFirstNamedChild(root, "authorization");
		if (auth == null) {
			auth = doc.createElement("authorization");
			root.appendChild(auth);
		}
		Element read = XmlUtil.getFirstNamedChild(auth, "read");
		if (read == null) {
			read = doc.createElement("read");
			auth.appendChild(read);
		}
		String readText = read.getTextContent();
		if (readText.contains("*")) {
			readText = readText
						.replace( "*", "" )
							.replaceAll( "\\s+", "")
								.replaceAll( ",,+", "," );
			if (readText.startsWith(",")) readText = readText.substring(1);
			if (readText.endsWith(",")) readText = readText.substring(0, readText.length()-2);
			read.setTextContent( readText );
		}
	}

	/**
	 * Make the document public.
	 */
	public void makePublic() {
		Element root = doc.getDocumentElement();
		Element auth = XmlUtil.getFirstNamedChild(root, "authorization");
		if (auth == null) {
			auth = doc.createElement("authorization");
			root.appendChild(auth);
		}
		Element read = XmlUtil.getFirstNamedChild(auth, "read");
		if (read == null) {
			read = doc.createElement("read");
			auth.appendChild(read);
		}
		String readText = read.getTextContent().trim();
		if (!readText.contains("*")) {
			if (readText.equals("")) readText = "*";
			else {
				readText = "*," + readText;
				readText = readText
							.replaceAll( "\\s+", "")
								.replaceAll( ",,+", "," );
			}
			read.setTextContent( readText );
		}
	}

    /**
     * Determine whether the MircDocument contains an image
     * element referencing a specific file.
     * @param name the filename.
     * @return true if the document contains a reference to the file; false otherwise.
     */
	public boolean containsImage(String name) {
		if (doc == null) return false;
		NodeList imageList = doc.getDocumentElement().getElementsByTagName("image");
		for (int i=0; i<imageList.getLength(); i++) {
			Element image = (Element)imageList.item(i);
			if (image.getAttribute("src").equals(name)) return true;
			NodeList altList = image.getElementsByTagName("alternative-image");
			for (int k=0; k<altList.getLength(); k++) {
				Element alt = (Element)altList.item(k);
				if (alt.getAttribute("src").equals(name)) return true;
			}
		}
		return false;
	}

    /**
     * Determine whether a user is authorized for a specified
     * action on the MIRCdocument. Possible actions are read, update, export,
     * and delete. An administrator and a document owner are authorized
     * to take any action. Other users are subject to the constraints
     * imposed in the authorization element of the MIRCdocument.
     * @param action the requested action.
     * @param user the requesting user.
     */
	public boolean authorizes(String action, User user) {
		try {
			if (user != null) {
				//The admin user is allowed to do anything
				if (user.hasRole("admin")) return true;

				//The owner is authorized to do anything.
				if (hasOwner(user)) return true;
			}

			//For the delete action, only the owner or admin is ever authorized
			//to delete a non-draft document, but any author can delete a draft
			//document, so if the document is not draft, return false now.
			if (action.equals("delete")) return isDraft();

			//For non-owners or non-authenticated users, the rule is that if an action
			//authorization does not exist in the document, read and export actions are
			//authorized, but update actions are not.

			//See if the action authorization exists in the document.
			Element auth = XmlUtil.getFirstNamedChild(doc, "authorization");
			Element actionRoles = XmlUtil.getFirstNamedChild(auth, action);
			if (actionRoles == null) return !action.equals("update");

			//Get the list of roles
			String roleList = actionRoles.getTextContent().trim();

			//See if the list is blank, which authorizes nobody.
			if (roleList.equals("")) return false;

			//See if the list includes an asterisk, which authorizes everybody.
			if (roleList.contains("*")) return true;

			//Anything else requires an authenticated user
			if (user == null) return false;

			//It is not a blanket authorization; check the roles individually.
			//The list can be separated by commas or whitespace.
			//If a specific user is included, it must be placed in [...].
			String username = user.getUsername();
			String[] roles = roleList.replaceAll("[,\\s]+",",").split(",");
			for (String role : roles) {
				role = role.trim();
				if (role.startsWith("[") && role.endsWith("]")) {
					//It's a username, see if it is the current user.
					role = role.substring(1,role.length()-1).trim();
					if (role.equals(username)) return true;
				}
				else if (user.hasRole(role)) return true;
			}
		}
		catch (Exception e) { }
		return false;
	}

    /**
     * Get a Set containing the usernames of all the owners.
     */
	public Set<String> getOwnersSet() {
		HashSet<String> set = new HashSet<String>();
		Element auth = XmlUtil.getFirstNamedChild(doc, "authorization");
		Element ownerElement = XmlUtil.getFirstNamedChild(auth, "owner");
		if (ownerElement == null) return set;
		String ownerText = ownerElement.getTextContent().trim();
		String[] owners = ownerText.replaceAll("[\\[\\],\\s]+",",").split(",");
		for (String owner : owners) {
			owner = owner.trim();
			if (!owner.equals("")) set.add(owner);
		}
		return set;
	}

    /**
     * Get a String array containing the usernames of all the owners.
     */
	public String[] getOwners() {
		Set<String> set = getOwnersSet();
		return set.toArray(new String[set.size()]);
	}

    /**
     * Determine whether a user is an owner of the MIRCdocument.
     * @param user the user.
     */
	public boolean hasOwner(User user) {
		if (user != null) {
			Set set = getOwnersSet();
			return set.contains(user.getUsername());
		}
		return false;
	}

    /**
     * Determine whether this object contains a manifest element.
     * @return true if the manifest element exists as a first-generation
     * child of the root element; false otherwise.
     */
	public boolean isManifest() {
		Element manifest = XmlUtil.getFirstNamedChild(doc, "manifest");
		return (manifest != null);
	}

    /**
     * Get a String array of the instances contained in the manifest element.
     * @return the array of instances, or an empty array if the object is
     * not a manifest.
     */
	public String[] getInstanceList() {
		LinkedList<String> list = new LinkedList<String>();
		Element manifest = XmlUtil.getFirstNamedChild(doc, "manifest");
		if (manifest != null) {
			NodeList nl = manifest.getElementsByTagName("uid");
			for (int i=0; i<nl.getLength(); i++) {
				list.add( nl.item(i).getTextContent().trim() );
			}
		}
		return list.toArray( new String[ list.size() ] );
	}

    /**
     * Remove the manifest element.
     */
	public void removeManifestElement() {
		Element manifest = XmlUtil.getFirstNamedChild(doc, "manifest");
		if (manifest != null) {
			manifest.getParentNode().removeChild(manifest);
		}
	}

	/**
	 * Insert a file into the MircDocument.
	 * @param file the file to be inserted.
	 * @param unpackZipFiles true if a zip file is to be unpacked and its contents
	 * inserted; false if a zip file is to to be inserted as a zip file. Note: zip
	 * files are only unpacked one level deep.
	 * @param textExtensions an array of extensions corresponding to text files. Text
	 * files are inserted as metadata objects, and their contents are inserted as
	 * defined in the insert(File) method.
	 * @param anonymize true if DicomObjects are to be anonymized; false otherwise.
	 */
	public void insertFile(File file,
						   boolean unpackZipFiles,
						   String[] textExtensions,
						   boolean anonymize) throws Exception {
		if (file.isFile()) {
			FileObject object = FileObject.getInstance(file);
			insertObject(object, unpackZipFiles, textExtensions, anonymize);
		}
	}

	/**
	 * Replace threadblock elements in this MircDocument with ones from another
	 * MircDocument. This method only replaces threadblock elements that have id
	 * attributes that correspond with those in the other MircDocument.
	 * @param md the source MircDocument used to obtain threadblock elements..
	 */
	public void updateThreadBlocks(MircDocument md) {
		try {
			Element srcRoot = md.getXML().getDocumentElement();
			NodeList srcNL = srcRoot.getElementsByTagName("threadblock");
			Element thisRoot = doc.getDocumentElement();
			NodeList thisNL = thisRoot.getElementsByTagName("threadblock");
			for (int i=0; i<thisNL.getLength(); i++) {
				Element tb = (Element)thisNL.item(i);
				String id = tb.getAttribute("id");
				for (int k=0; k<srcNL.getLength(); k++) {
					Element srcTB = (Element)srcNL.item(i);
					String srcID = srcTB.getAttribute("id");
					if (id.equals(srcID)) {
						Node importedTB = doc.importNode(srcTB, true);
						tb.getParentNode().replaceChild(importedTB, tb);
						break;
					}
				}
			}
		}
		catch (Exception ignore) { }
	}

	/**
	 * Insert an object into the MircDocument.
	 * @param object the object to be inserted.
	 * @param unpackZipFiles true if a zip file is to be unpacked and its contents
	 * inserted; false if a zip file is to to be inserted as a zip file. Note: zip
	 * files are only unpacked one level deep.
	 * @param textExtensions an array of extensions corresponding to text files. Text
	 * files are inserted as metadata objects, and their contents are inserted as
	 * defined in the insert(File) method.
	 * @param anonymize true if DicomObjects are to be anonymized; false otherwise.
	 */
	public void insertObject(FileObject object,
							 boolean unpackZipFiles,
							 String[] textExtensions,
							 boolean anonymize) throws Exception {
		object.setStandardExtension();
		object.filterFilename("%20","_");
		object.filterFilename("\\s+","_");

		if ((object instanceof ZipObject) && unpackZipFiles) {
			ZipObject zObject = (ZipObject)object;
			ZipEntry[] entries = zObject.getEntries();
			for (int i=0; i<entries.length; i++) {
				try {
					//Okay, we have to be a bit careful not to overwrite
					//a file that has already been inserted into the MIRCdocument.
					File zFile = new File(entries[i].getName().replace('/', File.separatorChar));
					zFile = new File(docDir, zFile.getName());
					if (zFile.exists()) {
						zFile = File.createTempFile("ALT-", "-"+zFile.getName(), docDir);
					}
					//Copy the file into the MIRCdocument's directory.
					zObject.extractFile(entries[i], docDir, zFile.getName());
					//Insert the file. Only unpack one level deep.
					insertFile(zFile, false, textExtensions, anonymize);
				}
				catch (Exception ignore) { }
			}
			zObject.getFile().delete();
		}
		else {
			//Move the file to the directory with the MIRCdocument,
			//changing the name if a file with that name already
			//exists in the directory.
			object.moveToDirectory(docDir);

			//Anonymize it if necessary
			if (anonymize && (object instanceof DicomObject)) {
				DicomAnonymizer fsa = MircConfig.getInstance().getFileServiceDicomAnonymizer();
				if (fsa != null) {
					DAScript dascript = DAScript.getInstance(fsa.getScriptFile());
					Properties script = dascript.toProperties();
					Properties lookup = LookupTable.getProperties(fsa.getLookupTableFile());
					IntegerTable intTable = fsa.getIntegerTable();
					File file = object.getFile();
					DICOMAnonymizer.anonymize(file, file, script, lookup, intTable, false, false);
					object = new DicomObject(file);
					object.renameToUID();
				}
			}

			//See if we can instantiate it as a MircImage.
			//If successful, add it in as an image.
			try {
				MircImage image = new MircImage( object.getFile() );
				if (image.isDicomImage()) {
					insertDicomElements( image.getDicomObject() );
				}
				insert(image);
			}
			catch (Exception notImage) {
				//The file is not an image that can be inserted,
				//Insert it into the document as a metadata object.
				insert( object, object.getFile().getName() );
				if (object.hasMatchingExtension( textExtensions, true )) {
					insert( object.getFile() );
				}
			}
		}
	}

	/**
	 * Insert key elements into the MircDocument.
	 * This method is used by the Zip Service.
	 * It does not re-index the document.
	 * @param title the title of the document.
	 * @param name the author's name.
	 * @param affiliation the author's affiliation.
	 * @param contact the author's contact information.
	 * @param abstracttext the abstract of the document.
	 * @param keywords the keywords.
	 * @param owner the username of the owner of the document.
	 * @param read the read privilege for the document.
	 * @param update the update privilege for the document.
	 * @param export the export privilege for the document.
	 * @param overwriteTemplate true if supplied parameters are
	 * to overwrite the values in the template; false if the template
	 * parameters are not to be overwritten. Note: the name,
	 * affiliation, contact, and username are always overwritten,
	 * since the owner must be the user who is creating the document.
	 */
	public synchronized void insert(
				String title,
				String name,
				String affiliation,
				String contact,
				String abstracttext,
				String keywords,
				String owner,
				String read,
				String update,
				String export,
				boolean overwriteTemplate
				) {

		Element root = doc.getDocumentElement();

		if (!title.trim().equals("") && overwriteTemplate)
			replaceElementValue(root, "title", title);

		if (!name.trim().equals(""))
			replaceElementValue(root, "author/name", name);

		if (!affiliation.trim().equals(""))
			replaceElementValue(root, "author/affiliation", affiliation);

		if (!contact.trim().equals(""))
			replaceElementValue(root, "author/contact", contact);

		if (!owner.trim().equals(""))
			replaceElementValue(root, "authorization/owner", owner);

		if (overwriteTemplate) {
			replaceElementValue(root, "abstract", abstracttext);
			replaceElementValue(root, "keywords", keywords);
			replaceElementValue(root, "authorization/read", read);
			replaceElementValue(root, "authorization/update", update);
			replaceElementValue(root, "authorization/export", export);
		}
		//Set the publication date
		setPublicationDate(root);
	}

	/**
	 * Insert a text file into the MircDocument, creating a Notes section
	 * if one does not exist.
	 * @param file the text file to insert into the MircDocument.
	 */
	public synchronized void insert(File file) {

		Element root = doc.getDocumentElement();
		Element notes = getNotesSection(root);

		//Put in the paragraphs. Paragraphs are denoted by two newlines,
		//separated only by whitespace.
		String text = FileUtil.getText(file);
		String[] pArray = text.split("\\n\\s*\\n");
		for (int i=0; i<pArray.length; i++) {
			String pString = pArray[i].trim();
			if (!pString.equals("")) {
				Element pNode = doc.createElement("p");
				//Note: we do not escape the characters in pString because
				//that happens automatically when the Text node is created.
				Text textNode = doc.createTextNode(pString);
				pNode.appendChild(textNode);
				notes.appendChild(pNode);
			}
		}
	}

	/**
	 * Insert a MircImage into the MircDocument, creating any necessary JPEGs.
	 * This method stores the images in the directory, but like all other
	 * methods in this class, it does not store the text of the document.
	 * @param image the image to insert into the MircDocument.
	 */
	public synchronized void insert(MircImage image) throws Exception {
		//Handle any insert-megasave elements
		insertMegasave(image);

		//And handle any insert-image elements
		insertImage(image);
	}

	/**
	 * Insert a FileObject into the MircDocument. This method is used by the
	 * Dicom Service to insert metadata files.
	 * @param fileObject the object to insert into the MircDocument.
	 * @param name the object to insert into the MircDocument.
	 */
	public void insert(FileObject fileObject, String name) {

		//Move the object to the document's directory.
		//Note that this allows duplicates so that if multiple
		//metadata objects are received, they will all be stored
		//and indexed.
		fileObject.moveToDirectory(docDir, name);

		//Set the extension of the file.
		//(Note: when the object was moved, the name may have changed;
		//we need to set the extension after the move to ensure that the
		//name doesn't put an incorrect extension on the object.)
		fileObject.setStandardExtension();

		//Look for the insertion point
		NodeList nl = doc.getDocumentElement().getElementsByTagName("metadata-refs");
		if (nl.getLength() > 0) {
			Element mdrs = (Element)nl.item(0);
			Element mdr = doc.createElement("metadata");
			mdr.setAttribute("href", fileObject.getFile().getName());
			appendChild(mdr, "type", fileObject.getType());
			appendChild(mdr, "date", fileObject.getDate());
			appendChild(mdr, "desc", fileObject.getDescription());
			mdrs.appendChild(mdr);
		}
	}

	private void appendChild(Element el, String name, String value) {
		Element child = el.getOwnerDocument().createElement(name);
		child.setTextContent(value);
		el.appendChild(child);
	}

	/**
	 * Insert a DicomObject's element contents into the MircDocument by processing
	 * the document, fetching elements from the DicomObject in response to
	 * DICOM element commands (g....e....). This method does not actually insert
	 * the DicomObject into the document; it only inserts elements, not
	 * references to images or metadata objects. IMPORTANT NOTE: this method does
	 * NOT anonymize the DicomObject.
	 * @param dicomObject the object whose elements are to be inserted into the MircDocument.
	 */
	public void insertDicomElements(DicomObject dicomObject) throws Exception {

		Element root = doc.getDocumentElement();
		processElement( root, dicomObject );

		//Update the modality, preventing duplicates
		Element modality = XmlUtil.getFirstNamedChild(root, "modality");
		if (modality != null) {
			String modalityText = modality.getTextContent().trim();
			String mod = dicomObject.getElementValue("Modality", "").trim();
			if (!mod.equals("")) {
				String[] mods = modalityText.split(",");
				boolean found = false;
				for (String m : mods) {
					if (m.trim().equals(mod)) {
						found = true;
						break;
					}
				}
				if (!found) {
					if (!modalityText.equals("")) modalityText += ",";
					modalityText += mod;
					modality.setTextContent(modalityText);
				}
			}
		}
	}

	/**
	 * Insert a DicomObject into the MircDocument, creating any necessary JPEGs.
	 * This method is used by the DICOM Service to insert DICOM objects.
	 * @param dicomObject the object to insert into the MircDocument.
	 * @param allowOverwrite true if the object is to be allowed to overwrite another
	 * object with the same name in the document; false if a new name is to be assigned
	 * if necessary to avoid duplicates.
	 */
	public void insert(DicomObject dicomObject, boolean allowOverwrite) throws Exception {

		//Set the extension.
		dicomObject.setStandardExtension();

		//Make sure the DicomObject is an image
		if (!dicomObject.isImage()) {
			//It's not; handle the special cases and then
			//treat the object as a metadata object.
			if (dicomObject.isManifest()) {
				insertManifestData(dicomObject);
			}
			else if (dicomObject.isAdditionalTFInfo()) {
				try { insertAdditionalTFInfo(dicomObject); }
				catch (Exception ex) { }
			}
			//insert(dicomObject, dicomObject.getFile().getName());
			return;
		}

		//Move the object into the document's directory,
		//allowing duplicates only if enabled. Note that this might
		//change the name of the dicomObject. First, though, make
		//a clone so we can be certain that the original file is
		//removed from the queue.
		File tempClone = new File(dicomObject.getFile().getAbsolutePath());
		dicomObject.moveToDirectory(docDir, allowOverwrite);
		tempClone.delete();

		//If the file is already in the document, don't modify the document,
		//but call the insertXXX methods anyway so that any changes
		//in the DicomObject (for example, WW/WL) can be reflected
		//in the JPEGs.
		//Note that if the name was changed in the moveToDirectory call,
		//a new instance of the object will be inserted and the text will
		//be modified.
		String name = dicomObject.getFile().getName();
		boolean modifyDoc = !containsImage(name);

		//Handle any insert-megasave elements
		insertMegasave(dicomObject, modifyDoc);

		//And handle any insert-image elements
		insertImage(dicomObject, modifyDoc);
	}

	//Insert data from a TCE manifest.
	private void insertManifestData(DicomObject dicomObject) {

		Element root = doc.getDocumentElement();

		//Try to get an author's name
		String[] observerList = dicomObject.getObserverList();
		if ((observerList != null) && (observerList.length > 0)) {
			String name = observerList[0];
			String[] names = name.split("\\^");
			if (names.length > 1) {
				name = names[0];
				for (int i=names.length-1; i>0; i--) name = names[i]+" "+name;
			}
			replaceElementValue(root, "author/name", name);
		}

		//Don't put in the modality from the manifest since it is always "KO".
		//replaceElementValue(root,"modality",dicomObject.getModality());

		//Now put in the Key Object Description, if it is there.
		String kodText = dicomObject.getKeyObjectDescription();
		if (kodText != null) {
			//Put the entire text in the section with the heading "Notes".
			Element notes = getNotesSection(root);

			//Break it into lines and put it all in a paragraph.
			String[] lines = kodText.split("\\n");
			String title = StringUtil.getDateTime(" at ");
			title = "Manifest Processed on " + title;
			Element p = doc.createElement("p");
			Element b = doc.createElement("b");
			b.appendChild(doc.createTextNode(title));
			p.appendChild(b);
			p.appendChild(doc.createElement("br"));
			p.appendChild(doc.createElement("br"));
			for (int i=0; i<lines.length; i++) {
				p.appendChild(doc.createTextNode(lines[i]));
				p.appendChild(doc.createElement("br"));
			}
			notes.appendChild(p);

			//Now parse the text and update any elements.
			Hashtable<String,String> table = dicomObject.getParsedKOD(kodText);
			if (table != null) insert(root, table);
		}
	}

	//Find the section element containing the notes.
	//This method creates a Notes section if one does not exist.
	private Element getNotesSection(Element root) {
		Node child = root.getFirstChild();
		while (child != null) {
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				Element el = (Element)child;
				if (el.getTagName().equals("section")
						&& el.getAttribute("heading").equals("Notes")) {
					return el;
				}
			}
			child = child.getNextSibling();
		}
		//Didn't find one, create it.
		Element section = root.getOwnerDocument().createElement("section");
		section.setAttribute("heading", "Notes");
		root.appendChild(section);
		return section;
	}

	//Insert data from a TCE Additional Teaching File Info object.
	private void insertAdditionalTFInfo(DicomObject dicomObject) {

		Element root = doc.getDocumentElement();

		//Get the Additional Teaching File Info.
		Hashtable<String,String> table = dicomObject.getAdditionalTFInfo();
		if (table != null) {
			//Okay, it's there. update any elements.
			insert(root, table);

			//Now process the document, looking for any ATFI-xxx elements
			//and replace them with the corresponding contents from the table.
			processATFIElements(root, table);
		}
	}

	//Replace elements with tag names starting with ATFI- with the contents of
	//the table entry with the corresponding suffix (e.g. ATFI-abstract is
	//replaced by a text node containing the text from the abstract element
	//in the table.
	private void processATFIElements(Element el, Hashtable<String,String> table) {
		String elName = el.getTagName();
		if (elName.startsWith("ATFI-")) {
			String key = elName.substring(5);
			String value = table.get(key);
			if (value != null) {
				Node text = el.getOwnerDocument().createTextNode(value);
				Node parent = el.getParentNode();
				parent.replaceChild(text, el);
			}
		}
		else {
			Node child = el.getFirstChild();
			while (child != null) {
				Node nextChild = child.getNextSibling();
				if (child instanceof Element) processATFIElements((Element)child, table);
				child = nextChild;
			}
		}
	}

	//Insert the contents of a Hashtable by listing all the keys and replacing the value of any
	//element whose path from the root of the MircDocument is equal to the name of the key.
	//This method will not modify any element that contains child elements.
	private void insert(Element root, Hashtable<String,String> table) {
		try {
			for (String key : table.keySet()) {
				String value = table.get(key);
				replaceElementValue(root, key, value);
			}
		}
		catch (Exception ex) { }
	}

	//Replace the value of an element with text.
	private void replaceElementValue(Element root, String path, String value) {
		Document owner = root.getOwnerDocument();
		Element el = XmlUtil.getElementViaPath(root, root.getTagName() + "/" +path);
		if ((el != null) && (value != null)) {
			if (el.getTagName().equals("title") && (el.getTextContent().trim().toLowerCase().equals("untitled")))  {
				//Handle title elements specially because some templates have "Untitled" in the title element.
				Node child;
				while ((child = el.getFirstChild()) != null) el.removeChild(child);
				setElementChildren(el, value);
			}
			else if (el.getTagName().equals("name") && el.getParentNode().getNodeName().equals("author"))  {
				//Handle name elements specially so any template value can be removed.
				Node child;
				while ((child = el.getFirstChild()) != null) el.removeChild(child);
				setElementChildren(el, value);
			}
			else if (el.getTagName().equals("abstract") ||
					 el.getParentNode().getNodeName().equals("abstract")) {
				//Handle abstract elements specially because of the requirement
				//that abstract contents be wrapped in paragraph tags. Some
				//templates have the paragraphs in them, and some do not.
				//Further, AFTI objects don't supply the p tag in the path while
				//some PACS vendors' implementations of the KeyObjectDescription
				//elements in TCE manifests do. The strategy here is to remove
				//all paragraph children of the abstract element that have the text content
				//"None." and then wrap the supplied value in paragraphs, splitting
				//it on double-newlines.
				if (!el.getTagName().equals("abstract")) el = (Element)el.getParentNode();
				Node child = el.getFirstChild();
				while (child != null) {
					if (child.getNodeType() == Node.ELEMENT_NODE) {
						if (child.getTextContent().trim().toLowerCase().equals("none.")) el.removeChild(child);
					}
					child = child.getNextSibling();
				}
				insertParagraphs(el, value);
			}
			else {
				//This is a normal element found from a path,
				//just insert the value in paragraphs.
				setElementChildren(el, value);
			}
		}
		//Now try to find a section element with a heading like the element being
		//replaced and add the value to its children. Only do this for paths
		//consisting of a single element.
		if (!path.contains("/")) {
			NodeList nl = root.getElementsByTagName("section");
			for (int i=0; i<nl.getLength(); i++) {
				Element section = (Element)nl.item(i);
				String heading = section.getAttribute("heading").toLowerCase();
				if (path.equals(heading)) insertParagraphs(section, value);
			}
		}
	}

	private void insertParagraphs(Element el, String value) {
		//Break the value into paragraphs by double newlines
		//and append them to the element.
		String[] pArray = value.split("\\n\\s*\\n");
		for (int k=0; k<pArray.length; k++) {
			String pString = pArray[k].trim();
			if (!pString.equals("")) {
				Element pNode = el.getOwnerDocument().createElement("p");
				setElementChildren(pNode,pString);
				el.appendChild(pNode);
			}
		}
	}

	private void replaceElementValue(Element el, String value) {
		//Make sure that there are no child elements.
		Node child;
		if (!XmlUtil.hasChildElements(el)) {
			while ((child = el.getFirstChild()) != null) el.removeChild(child);
			//Now parse the value and append its nodes to the element.
			setElementChildren(el, value);
		}
	}

	//Parse a text string and append its nodes as children of an element.
	//If the text doesn't parse, then put it all in a text node and append that to the element.
	private void setElementChildren(Element el, String value) {
		try {
			Document valueDoc = XmlUtil.getDocument("<root>"+value+"</root>");
			Document elDoc = el.getOwnerDocument();
			Element valueRoot = valueDoc.getDocumentElement();
			Node vNode = valueRoot.getFirstChild();
			while (vNode != null) {
				Node elNode = elDoc.importNode(vNode, true);
				el.appendChild(elNode);
				vNode = vNode.getNextSibling();
			}
		}
		catch (Exception ex) {
			//There was an error, just insert the value as a text node.
			Text text = el.getOwnerDocument().createTextNode(value);
			el.appendChild(text);
		}
	}

	//*********************************************************************************************
	//
	//	Update images and series with new WW and WL values
	//
	//*********************************************************************************************
	/**
	 * Update an image, saving it with the specified parameters.
	 * @param dicomObject the object to use in creating the JPEGs.
	 * @param frame the frame to use in creating JPEGs.
	 * @param wl the window level to use in creating JPEGs.
	 * @param ww the window width to use in creating JPEGs.
	 * @param q the quality level to use in creating JPEGs.
	 */
	public void updateImage(DicomObject dicomObject, int frame, int q, int wl, int ww) throws Exception {
		File imageFile = dicomObject.getFile();
		String name = imageFile.getName();
		Element root = getXML().getDocumentElement();
		NodeList imageSections = root.getElementsByTagName("image-section");
		for (int i=0; i<imageSections.getLength(); i++) {
			Element imageSection = (Element)imageSections.item(i);
			NodeList alts = root.getElementsByTagName("alternative-image");
			for (int k=0; k<alts.getLength(); k++) {
				Element alt = (Element)alts.item(k);
				if (alt.getAttribute("role").equals("original-format") && alt.getAttribute("src").equals(name)) {
					Element baseImage = (Element)alt.getParentNode();
					saveWWWLImages(dicomObject, baseImage, frame, q, wl, ww);
				}
			}
		}
	}

	public void updateSeries(DicomObject dicomObject, int frame, int q, int wl, int ww) throws Exception {
		//First, find the study and series information as stored in the order-by element for the DicomObject in the MircDocument.
		String study = null;
		String series = null;
		File imageFile = dicomObject.getFile();
		String name = imageFile.getName();
		Element root = getXML().getDocumentElement();
		NodeList imageSections = root.getElementsByTagName("image-section");
		for (int i=0; i<imageSections.getLength(); i++) {
			Element imageSection = (Element)imageSections.item(i);
			NodeList alts = root.getElementsByTagName("alternative-image");
			for (int k=0; k<alts.getLength(); k++) {
				Element alt = (Element)alts.item(k);
				if (alt.getAttribute("role").equals("original-format") && alt.getAttribute("src").equals(name)) {
					//Okay, we found the DicomObject in the MircDocument, see if it has an order-by element.
					Element baseImage = (Element)alt.getParentNode();
					NodeList obs = baseImage.getElementsByTagName("order-by");
					if (obs.getLength() > 0) {
						Element ob = (Element)obs.item(0);
						study = ob.getAttribute("study");
						series = ob.getAttribute("series");
						break;
					}
				}
			}
			if (series !=  null) break;
		}
		if (series == null) {
			//We didn't find an order-by element to use to identify the series.
			//The best we can do is try to update the image itself.
			updateImage(dicomObject, frame, q, wl, ww);
			return;
		}

		//Okay, if we get here, we know the study and series information.
		//Look for all images that match the study and series and update them.
		//Note that this will also pick up the identified image.
		//Note also that we still have the nodelist of image-sections.
		for (int i=0; i<imageSections.getLength(); i++) {
			Element imageSection = (Element)imageSections.item(i);
			NodeList obs = root.getElementsByTagName("order-by");
			for (int k=0; k<obs.getLength(); k++) {
				Element ob = (Element)obs.item(k);
				if (ob.getAttribute("study").equals(study) && ob.getAttribute("series").equals(series)) {
					//Okay, we found an object in the study and series; now get the DicomObject for it
					Element baseImage = (Element)ob.getParentNode();
					NodeList alts = baseImage.getElementsByTagName("alternative-image");
					for (int x=0; x<alts.getLength(); x++) {
						Element alt = (Element)alts.item(x);
						if (alt.getAttribute("role").equals("original-format")) {
							try {
								DicomObject dob = new DicomObject(new File(docDir, alt.getAttribute("src")));
								saveWWWLImages(dob, baseImage, frame, q, wl, ww);
							}
							catch (Exception skip) { }
						}
					}
				}
			}
		}
	}

	private void saveWWWLImages(DicomObject dicomObject, Element baseImage, int frame, int q, int wl, int ww) throws Exception {
		if (baseImage == null) return;
		saveWWWLImage(dicomObject, baseImage, frame, q, wl, ww);
		Node child = baseImage.getFirstChild();
		while (child != null) {
			if (child instanceof Element) {
				saveWWWLImage(dicomObject, (Element)child, frame, q, wl, ww);
			}
			child = child.getNextSibling();
		}
	}

	private void saveWWWLImage(DicomObject dob, Element el, int frame, int q, int wl, int ww) throws Exception {
		if (el == null) return;
		if (frame == -1) frame = dob.getNumberOfFrames() / 2;
		String tag = el.getTagName();
		String attr = el.getAttribute("role");
		if (tag.equals("image") || (tag.equals("alternative-image") && (attr.equals("icon") || attr.equals("original-dimensions")))) {
			String src = el.getAttribute("src");
			int w = StringUtil.getInt(el.getAttribute("w"), -1);
			int h = StringUtil.getInt(el.getAttribute("h"), -1);
			File file = new File (docDir, src);
			if (!file.delete()) logger.info("Unable to delete "+file+" before saving");
			//logger.info("Saving window-leveled JPEG: "+file.getAbsoluteFile()+" ("+wl+","+ww+") ("+w+","+h+")");
			dob.saveAsWindowLeveledJPEG(file, w, h, frame, q, wl, ww);

			/*
			//This code was used for testing, to find the problem where it worked only if the image was not scaled.
			int x = src.lastIndexOf(".jpeg");
			if (x > 0) {
				String xsrc = src.substring(0, x) +"["+wl+","+ww+"].jpeg";
				File xfile = new File(docDir, xsrc);
				dob.saveAsWindowLeveledJPEG(xfile, -1, -1, frame, q, wl, ww);
			}
			*/
		}
	}

	//*********************************************************************************************
	//
	//	Insert images
	//
	//*********************************************************************************************

	//Handle the insert-megasave element for DicomObjects.
	//This method is called in services that create MIRCdocuments automatically.
	private void insertMegasave(DicomObject dicomObject, boolean modifyDoc) {

		//Make sure this is an image
		if (!dicomObject.isImage()) return;

		//Look for the insert point
		Element root = doc.getDocumentElement();
		NodeList nl = root.getElementsByTagName("insert-megasave");

		//If we can't find the place, just return without modifying anything
		if (nl.getLength() == 0) return;
		Element insertionPoint = (Element)nl.item(0);

		//Get the parent element and make sure it is an image-section
		Element parent = (Element)insertionPoint.getParentNode();
		if (!parent.getTagName().equals("image-section")) return;

		boolean suppressOriginalFormat = insertionPoint.getAttribute("suppressOriginalFormat").equals("yes");

		//Get the paneWidth from the image-section element.
		//This is the space allocated in the display for the images.
		int paneWidth = StringUtil.getInt( parent.getAttribute("image-pane-width"), 700 );

		//Get the width attribute from the insert-megasave element.
		//This is the maximum size JPEG to be created for any base image.
		int maxWidth = StringUtil.getInt( insertionPoint.getAttribute("width"), paneWidth );
		//Make sure the maximum width fits in the pane.
		if (maxWidth > paneWidth) maxWidth = paneWidth;

		//See if there are any min-* attributes.
		//minWidth is the minimum size JPEG to be created for any base image.
		int minWidth = StringUtil.getInt( insertionPoint.getAttribute("min-width"), 0 );

		//Get the image size;
		int imageWidth = dicomObject.getColumns();

		//Choose the frame
		int frame = dicomObject.getNumberOfFrames() / 2;

		//Make the JPEG images
		String name = dicomObject.getFile().getName();
		String nameNoExt = name.substring(0, name.lastIndexOf("."));
		Dimension d_base = dicomObject.saveAsJPEG(new File(docDir, nameNoExt+"_base.jpeg"), frame, maxWidth, minWidth, jpegQuality);
		Dimension d_icon = dicomObject.saveAsJPEG(new File(docDir, nameNoExt+"_icon.jpeg"), frame, 64, 0, -1);
		Dimension d_icon96 = dicomObject.saveAsJPEG(new File(docDir, nameNoExt+"_icon96.jpeg"), frame, 96, 0, -1); //for the author service

		//Make videos if possible
		boolean hasAVI = makeAVI(dicomObject, 2, true);
		//boolean hasMP4 = makeMP4(dicomObject, 2);

		//If we are to update the document, make the image element and put it just before the insert-megasave element.
		if (modifyDoc) {
			Element image = doc.createElement("image");
			image.setAttribute("src", nameNoExt+"_base.jpeg");
			image.setAttribute("w", Integer.toString(d_base.width));
			image.setAttribute("h", Integer.toString(d_base.height));

			Element icon = doc.createElement("alternative-image");
			icon.setAttribute("src", nameNoExt+"_icon.jpeg");
			icon.setAttribute("role", "icon");
			icon.setAttribute("w", Integer.toString(d_icon.width));
			icon.setAttribute("h", Integer.toString(d_icon.height));
			image.appendChild(icon);

			if (imageWidth > maxWidth) {
				Dimension d_full = dicomObject.saveAsJPEG(new File(docDir, nameNoExt+"_full.jpeg"), frame, imageWidth, 0, jpegQuality);
				Element full  = doc.createElement("alternative-image");
				full.setAttribute("src", nameNoExt+"_full.jpeg");
				full.setAttribute("role", "original-dimensions");
				full.setAttribute("w", Integer.toString(d_full.width));
				full.setAttribute("h", Integer.toString(d_full.height));
				image.appendChild(full);
			}

			if (hasAVI) {
				Element video  = doc.createElement("alternative-image");
				video.setAttribute("src", name+".avi");
				video.setAttribute("role", "video");
				image.appendChild(video);
			}

			if (!suppressOriginalFormat) {
				Element dcm = doc.createElement("alternative-image");
				dcm.setAttribute("src", name);
				dcm.setAttribute("role", "original-format");
				image.appendChild(dcm);
			}

			//Put in the order-by element to allow sorting
			try { image.appendChild( getOrderByElement(dicomObject, image) ); }
			catch (Exception ignore) { logger.warn("Unable to insert the order-by element"); }

			parent.insertBefore( image, insertionPoint );
		}

	}

	//Handle the insert-image element for DicomObjects.
	private void insertImage(DicomObject dicomObject, boolean modifyDoc) {

		//Make sure this is an image
		if (!dicomObject.isImage()) return;

		//Look for the insert point
		Element root = doc.getDocumentElement();
		NodeList nl = root.getElementsByTagName("insert-image");

		//If we can't find the place, just return without modifying anything
		if (nl.getLength() == 0) return;
		Element insertionPoint = (Element)nl.item(0);

		//Get the parent element
		Element parent = (Element)insertionPoint.getParentNode();

		//See if there are any width attributes
		int imageWidth = dicomObject.getColumns();
		int imageHeight = dicomObject.getRows();
		int maxWidth = StringUtil.getInt( insertionPoint.getAttribute("width"), imageWidth );
		int minWidth = StringUtil.getInt( insertionPoint.getAttribute("min-width"), 0 );

		//Choose the frame
		int frame = dicomObject.getNumberOfFrames() / 2;

		//Make the JPEG images
		String name = dicomObject.getFile().getName();
		String nameNoExt = name.substring(0,name.lastIndexOf("."));
		Dimension d_base = dicomObject.saveAsJPEG(new File(docDir,nameNoExt+"_base.jpeg"), frame, maxWidth, minWidth, jpegQuality);
		Dimension d_icon = dicomObject.saveAsJPEG(new File(docDir,nameNoExt+"_icon.jpeg"), frame, 64, 0, -1);
		Dimension d_icon96 = dicomObject.saveAsJPEG(new File(docDir,nameNoExt+"_icon96.jpeg"), frame, 96, 0, -1); //for the author service

		//If we are to update the document, make the image element and put it just before the insert-image element.
		if (modifyDoc) {
			Element image = doc.createElement("image");
			image.setAttribute("href", name);
			image.setAttribute("w", Integer.toString(imageWidth));
			image.setAttribute("h", Integer.toString(imageHeight));

			Element base = doc.createElement("image");
			base.setAttribute("src", nameNoExt+"_base.jpeg");
			base.setAttribute("w", Integer.toString(d_base.width));
			base.setAttribute("h", Integer.toString(d_base.height));
			image.appendChild(base);

			if (imageWidth > maxWidth) {
				dicomObject.saveAsJPEG(new File(docDir,nameNoExt+"_full.jpeg"), frame, imageWidth, 0, jpegQuality);
			}

			parent.insertBefore( image, insertionPoint );
		}
	}

	//Handle the insert-megasave element for MircImages.
	private void insertMegasave(MircImage mircImage) throws Exception {

		//Handle DicomObjects separately to simplify the logic below.
		if (mircImage.isDicomImage()) {
			insertMegasave( mircImage.getDicomObject(), true );
			return;
		}

		//Look for the insert point
		Element root = doc.getDocumentElement();
		NodeList nl = root.getElementsByTagName("insert-megasave");

		//If we can't find the place, just return without modifying anything
		if (nl.getLength() == 0) return;
		Element insertionPoint = (Element)nl.item(0);

		//Get the parent element and make sure it is an image-section
		Element parent = (Element)insertionPoint.getParentNode();
		if (!parent.getTagName().equals("image-section")) return;

		//Get the paneWidth from the image-section element.
		//This is the space allocated in the display for the images.
		int paneWidth = StringUtil.getInt( parent.getAttribute("image-pane-width"), 700 );

		//Get the width attribute from the insert-megasave element.
		//This is the maximum size JPEG to be created for any base image.
		int maxWidth = StringUtil.getInt( insertionPoint.getAttribute("width"), paneWidth );
		//Make sure the maximum width fits in the pane.
		if (maxWidth > paneWidth) maxWidth = paneWidth;

		//See if there are any min-* attributes.
		//minWidth is the minimum size JPEG to be created for any base image.
		int minWidth = StringUtil.getInt( insertionPoint.getAttribute("min-width"), 0 );

		//Get the image size;
		int imageWidth = mircImage.getColumns();
		int imageHeight = mircImage.getRows();

		//From here on, the logic is different from that for DicomObjects because
		//we try to use the original image as the base image if possible (in which
		//case we call it the full image just to make it clear to anyone looking
		//directly at the contents of the MIRCdocument's directory.

		//Make the JPEG images
		String name = mircImage.getFile().getName();
		String nameNoExt = name.substring(0, name.lastIndexOf("."));
		String ext = name.substring( name.lastIndexOf(".") + 1 );

		//Make the images we know we'll need. All the others depend on the sizes
		Dimension d_icon = mircImage.saveAsJPEG(new File(docDir, nameNoExt+"_icon.jpeg"), 0, 64, 0, -1);
		Dimension d_icon96 = mircImage.saveAsJPEG(new File(docDir, nameNoExt+"_icon96.jpeg"), 0, 96, 0, -1); //for the author service

		//Make a couple of booleans to make the rest of the code more readable.
		boolean imageFits = (imageWidth <= maxWidth);
		boolean standardImage = !mircImage.hasNonStandardImageExtension();

		//Make the image element and put it just before the insert-megasave element.
		Element image = doc.createElement("image");
		if (standardImage && imageFits) {
			image.setAttribute("src", nameNoExt+"_full."+ext);
			image.setAttribute("w", Integer.toString(imageWidth));
			image.setAttribute("h", Integer.toString(imageHeight));
			mircImage.renameTo(new File(docDir, nameNoExt+"_full."+ext));
		}
		else {
			Dimension d_base = mircImage.saveAsJPEG(new File(docDir, nameNoExt+"_base.jpeg"), 0, maxWidth, minWidth, jpegQuality);
			image.setAttribute("src", nameNoExt+"_base.jpeg");
			image.setAttribute("w", Integer.toString(d_base.width));
			image.setAttribute("h", Integer.toString(d_base.height));
		}

		Element icon = doc.createElement("alternative-image");
		icon.setAttribute("src", nameNoExt+"_icon.jpeg");
		icon.setAttribute("role", "icon");
		icon.setAttribute("w", Integer.toString(d_icon.width));
		icon.setAttribute("h", Integer.toString(d_icon.height));
		image.appendChild(icon);

		if (!standardImage || !imageFits) {
			Dimension d_full = mircImage.saveAsJPEG(new File(docDir, nameNoExt+"_full.jpeg"), 0, imageWidth, 0, jpegQuality);
			Element full  = doc.createElement("alternative-image");
			full.setAttribute("src", nameNoExt+"_full.jpeg");
			full.setAttribute("role", "original-dimensions");
			full.setAttribute("w", Integer.toString(d_full.width));
			full.setAttribute("h", Integer.toString(d_full.height));
			image.appendChild(full);
		}

		parent.insertBefore( image, insertionPoint );
	}

	//Handle the insert-image element for MircImages.
	private void insertImage(MircImage mircImage) throws Exception {

		//Look for the insert point
		Element root = doc.getDocumentElement();
		NodeList nl = root.getElementsByTagName("insert-image");

		//If we can't find the place, just return without modifying anything
		if (nl.getLength() == 0) return;
		Element insertionPoint = (Element)nl.item(0);

		//Get the parent element
		Element parent = (Element)insertionPoint.getParentNode();

		//See if there are any width attributes
		int imageWidth = mircImage.getColumns();
		int imageHeight = mircImage.getRows();
		int maxWidth = StringUtil.getInt( insertionPoint.getAttribute("width"), imageWidth );
		int minWidth = StringUtil.getInt( insertionPoint.getAttribute("min-width"), 0 );
		if (maxWidth > imageWidth) maxWidth = imageWidth; //???

		//Make the JPEG images
		String name = mircImage.getFile().getName();
		String nameNoExt = name.substring(0, name.lastIndexOf("."));
		String ext = name.substring( name.lastIndexOf(".") + 1 );

		//Choose the frame
		int frame = 0;
		if (mircImage.isDicomImage()) frame = mircImage.getDicomObject().getNumberOfFrames() / 2;

		Dimension d_icon = mircImage.saveAsJPEG(new File(docDir,nameNoExt+"_icon.jpeg"), frame, 64, 0, -1);
		Dimension d_icon96 = mircImage.saveAsJPEG(new File(docDir,nameNoExt+"_icon96.jpeg"), frame, 96, 0, -1); //for the author service

		//Make the image element and put it just before the insert-image element.
		Element image = doc.createElement("image");
		if (mircImage.isDicomImage()) {

			//It's a DICOM image; make the root image point to the original
			image.setAttribute("href", name);
			image.setAttribute("w", Integer.toString(imageWidth));
			image.setAttribute("h", Integer.toString(imageHeight));

 			//Make the child point to the base image.
			Dimension d_base = mircImage.saveAsJPEG(new File(docDir,nameNoExt+"_base.jpeg"), frame, maxWidth, minWidth, jpegQuality);
			Element base = doc.createElement("image");
			base.setAttribute("src", nameNoExt+"_base.jpeg");
			base.setAttribute("w", Integer.toString(d_base.width));
			base.setAttribute("h", Integer.toString(d_base.height));
			image.appendChild(base);
		}

		else if (imageWidth <= maxWidth) {
			//It's not a DicomObject and the original image fits.
			//There is no need for a link to the full image, so we
			//just make one image element and point to the original file.
			image.setAttribute("src", name);
			image.setAttribute("w", Integer.toString(imageWidth));
			image.setAttribute("h", Integer.toString(imageHeight));
		}

		else {
			//It's not a DicomObject and the original image does not fit.
			//Make the root image element point to the original image.
			image.setAttribute("href", name);
			image.setAttribute("w", Integer.toString(imageWidth));
			image.setAttribute("h", Integer.toString(imageHeight));

 			//Make the child point to the base image.
			Dimension d_base = mircImage.saveAsJPEG(new File(docDir,nameNoExt+"_base.jpeg"), frame, maxWidth, minWidth, jpegQuality);
			Element base = doc.createElement("image");
			base.setAttribute("src", nameNoExt+"_base.jpeg");
			base.setAttribute("w", Integer.toString(d_base.width));
			base.setAttribute("h", Integer.toString(d_base.height));
			image.appendChild(base);
		}

		parent.insertBefore( image, insertionPoint );

		//Create a caption text element if we need one
		int n = StringUtil.getInt( insertionPoint.getAttribute("caption").trim(), -1 );
		if (n != -1) {
			Element caption = doc.createElement("caption");
			caption.setAttribute("name", "CAP"+n);

			if (insertionPoint.getAttribute("jump-buttons").equals("yes")) {
				caption.setAttribute("jump-buttons", "yes");
			}

			if (insertionPoint.getAttribute("show-button").equals("yes")) {
				caption.setAttribute("show-button", "yes");
			}

			//Fix the document so the next caption will have the next value.
			//This is necessary to make all the name attributes different.
			insertionPoint.setAttribute( "caption", Integer.toString( n+1 ) );

			parent.insertBefore( caption, insertionPoint );
		}
	}

	//*********************************************************************************************
	//
	//	Video
	//
	//*********************************************************************************************

	/**
	 * Create an AVI from a multi-frame DicomObject. The AVI is created in the
	 * directory with the MIRCdocument. It has the same name as the DicomObject,
	 * plus the .avi extension.
	 * @param dicomObject the object
	 * @param minFrames the minimum number of frames to process. DicomObjects with fewer than
	 * this number of frames are not processed.
	 * @param jpeg true if the output format is to be JPEG; false if it is to be RAW
	 * @return true if an AVI was created; false otherwise.
	 */
	public boolean makeAVI(DicomObject dicomObject, int minFrames, boolean jpeg) {
		boolean done = false;
		int nFrames = dicomObject.getNumberOfFrames();
		AVIOutputStream out = null;

		if (dicomObject.isImage() && (nFrames > 0) && (nFrames >= minFrames)) {
			try {
				File aviFile = new File(docDir, dicomObject.getFile().getName()+".avi");
				int rows = dicomObject.getRows();
				int columns = dicomObject.getColumns();
				int rate = StringUtil.getInt( dicomObject.getElementValue("RecommendedDisplayFrameRate"), 10 );

				if (jpeg) {
					out = new AVIOutputStream(aviFile, AVIOutputStream.VideoFormat.JPG);
					out.setVideoDimension(columns, rows);
					out.setFrameRate(rate);
					File temp = File.createTempFile("DCM-", ".jpg");
					for (int frame=0; frame<nFrames; frame++) {
						dicomObject.saveAsJPEG(temp, frame, columns, columns, -1);
						out.writeFrame(temp);
					}
					temp.delete();
				}
				else {
					out = new AVIOutputStream(aviFile, AVIOutputStream.VideoFormat.RAW);
					out.setVideoDimension(columns, rows);
					out.setFrameRate(rate);
					for (int frame=0; frame<nFrames; frame++) {
						out.writeFrame(dicomObject.getScaledBufferedImage(frame, columns, columns));
					}
				}
				done = true;
			}
			catch (Exception ex) { logger.warn("Unable to create AVI", ex); }
			finally {
				if (out != null) {
					try { out.finish(); }
					catch (Exception unable) { }
					try { out.close(); }
					catch (Exception unable) { }
				}
			}
		}
		return done;
	}

	/**
	 * Create an MP4 from a multi-frame DicomObject. The MP4 is created in the
	 * directory with the MIRCdocument. It has the same name as the DicomObject,
	 * plus the .mp4 extension.
	 * @param dicomObject the object
	 * @param minFrames the minimum number of frames to process. DicomObjects with fewer than
	 * this number of frames are not processed.
	 * @return true if an MP4 was created; false otherwise.
	 */
	/*
	public boolean makeMP4(DicomObject dicomObject, int minFrames) {
		boolean done = false;
		int nFrames = dicomObject.getNumberOfFrames();
		TFSSequenceEncoder out = null;

		if (dicomObject.isImage() && (nFrames > 0) && (nFrames >= minFrames)) {
			try {
				File mp4File = new File(docDir, dicomObject.getFile().getName()+".mp4");
				int rows = dicomObject.getRows();
				int columns = dicomObject.getColumns();
				int rate = StringUtil.getInt( dicomObject.getElementValue("RecommendedDisplayFrameRate"), 10 );

				out = new TFSSequenceEncoder(mp4File, columns, rows,  rate);
				for (int frame=0; frame<nFrames; frame++) {
					out.encodeImage(dicomObject.getScaledBufferedImage(frame, columns, columns));
				}
				done = true;
			}
			catch (Exception ex) { logger.warn("Unable to create MP4", ex); }
			finally {
				if (out != null) {
					try { out.finish(); }
					catch (Exception unable) { }
				}
			}
		}
		return done;
	}
	*/

	//*********************************************************************************************
	//
	//	Methods for re-ordering image elements
	//
	//*********************************************************************************************

	/**
	 * Sort the image-section, if it exists. This method orders the images by:
	 * <ol>
	 * <li>StudyInstanceUID
	 * <li>SeriesNumber
	 * <li>AcquisitionNumber
	 * <li>InstanceNumber
	 *</ol>
	 */
	public void sortImageSection() {
		Element root = doc.getDocumentElement();
		Element imageSection = XmlUtil.getFirstNamedChild(root, "image-section");
		if (imageSection != null) {
			LinkedList<Element> imageList = new LinkedList<Element>();
			Node child = imageSection.getFirstChild();
			Node next;
			while (child != null) {
				next = child.getNextSibling();
				if (child.getNodeName().equals("image")) {

					//Add any missing order-by elements for DICOM images.
					Element orderby = XmlUtil.getFirstNamedChild(child, "order-by");
					if (orderby == null) addOrderByElement(child);

					//Now take the element out of the image-section and put it in the list
					imageList.add( (Element)imageSection.removeChild( child ) );
				}
				child = next;
			}
			if (imageList.size() > 0) {
				Element[] images = imageList.toArray(new Element[imageList.size()]);
				Arrays.sort( images, new ImageComparator() );
				Element insertionPoint = XmlUtil.getFirstNamedChild(imageSection, "insert-megasave");
				if (insertionPoint == null) insertionPoint = XmlUtil.getFirstNamedChild(imageSection, "insert-image");
				for (Element image : images) imageSection.insertBefore( image, insertionPoint );
			}
		}
	}

	private void addOrderByElement(Node image) {
		Node n = image.getFirstChild();
		while (n != null) {
			if (n.getNodeName().equals("alternative-image")) {
				Element e = (Element)n;
				if (e.getAttribute("role").equals("original-format")) {
					String src = e.getAttribute("src");
					if (src.toLowerCase().endsWith(".dcm"))
						try {
							DicomObject dob = new DicomObject( new File(docDir, src) );
							Element obe = getOrderByElement( dob, (Element)image );
							image.appendChild(obe);
						}
					catch (Exception ignore) { }
					return;
				}
			}
			n = n.getNextSibling();
		}
	}

	class ImageComparator implements Comparator {
		public ImageComparator() { }

		//Determine whether two image elements are in order by study, series, acquisition, and instance.
		//If the order-by child is missing from both elements, the elements are equal.
		//If the order-by child is missing from only one of the elements, that element is first.
		public int compare( Object a, Object b ) {
			Element aOrderBy = XmlUtil.getFirstNamedChild((Element)a, "order-by");
			Element bOrderBy = XmlUtil.getFirstNamedChild((Element)b, "order-by");

			if ((aOrderBy == null) && (bOrderBy == null)) return 0;
			if (aOrderBy == null) return -1;
			if (bOrderBy == null) return +1;
			int c;
			if ((c = compareInt(aOrderBy, bOrderBy, "date")) != 0) return c;
			if ((c = compareInt(aOrderBy, bOrderBy, "time")) != 0) return c;
			if ((c = compareText(aOrderBy, bOrderBy, "study")) != 0) return c;
			if ((c = compareInt(aOrderBy, bOrderBy, "series")) != 0) return c;
			if ((c = compareInt(aOrderBy, bOrderBy, "acquisition")) != 0) return c;
			return compareInt(aOrderBy, bOrderBy, "instance");
		}

		//Compare an attribute of two elements as text.
		//Return -1 if the attribute values are in sequence,
		//zero if they are the same, or +1 if they are out of order.
		private int compareText(Element a, Element b, String attrName) {
			return a.getAttribute(attrName).compareTo(b.getAttribute(attrName));
		}

		//Compare an attribute of two elements as integers.
		//Return -1 if the attribute values are in sequence,
		//zero if they are the same, or +1 if they are out of order.
		private int compareInt(Element a, Element b, String attrName) {
			long aValue = StringUtil.getLong(a.getAttribute(attrName), 0);
			long bValue = StringUtil.getLong(b.getAttribute(attrName), 0);
			if (aValue < bValue) return -1;
			if (aValue == bValue) return 0;
			return +1;
		}

		public boolean equals(Object obj) {
			return this.equals(obj);
		}
	}

	//Get a string containing the order-by element for a DicomObject
	static long oneDayInMillis = 24 * 3600 * 1000;
	private Element getOrderByElement(DicomObject dicomObject, Element image) throws Exception {
		Element orderBy = image.getOwnerDocument().createElement("order-by");

		try {
			GregorianCalendar gc = DateUtil.getCalendar( dicomObject.getStudyDate() );
			long days = gc.getTimeInMillis() / oneDayInMillis;
			orderBy.setAttribute("date", Long.toString(days));
			long time = DateUtil.getTime( dicomObject.getStudyTime() );
			orderBy.setAttribute("time", Long.toString(time));
		}
		catch (Exception ignore) { }

		orderBy.setAttribute("study", DigestUtil.hash(dicomObject.getStudyInstanceUID(), 6));
		orderBy.setAttribute("series", dicomObject.getSeriesNumber());
		orderBy.setAttribute("acquisition", dicomObject.getAcquisitionNumber());
		orderBy.setAttribute("instance", dicomObject.getInstanceNumber());

		String studyDesc = dicomObject.getStudyDescription().replace("\"", "").replace("\'", "");
		orderBy.setAttribute("study-desc", studyDesc);
		String seriesDesc = dicomObject.getSeriesDescription().replace("\"", "").replace("\'", "");
		orderBy.setAttribute("series-desc", seriesDesc);
		return orderBy;
	}

	//*********************************************************************************************
	//
	//	Insert RadLex term elements
	//
	//*********************************************************************************************

	/**
	 * Process the document and insert RadLex term elements.
	 */
	public synchronized void insertRadLexTerms() {
		try {
			//Get the MIRCdocument.
			Element root = doc.getDocumentElement();

			//Insert the terms
			insertRadLexTerms(root);
		}
		catch (Exception ex) { logger.debug("Unable to insert RadLex terms", ex); }
	}

	/**
	 * Insert RadLex terms.
	 * @param node the node on which to begin searching for RadLex terms.
	 */
	public static void insertRadLexTerms(Node node) {
		short type = node.getNodeType();
		if (type == Node.ELEMENT_NODE) {
			if (node.getNodeName().equals("term")) {
				//Replace the term node with its contents
				//and then process it. This will allow for
				//spelling corrections and changes in the
				//RadLex ontology.
				Document doc = node.getOwnerDocument();
				Node parent = node.getParentNode();
				String content = node.getTextContent();
				Text text = doc.createTextNode(content);
				parent.replaceChild(text, node);
				insertRadLexTerms(text);
			}
			else if (!skip.contains(node.getNodeName())) {
				Node child = node.getFirstChild();
				while (child != null) {
					//Note: get the next node now, before
					//anybody has a chance to remove the child
					Node next = child.getNextSibling();
					//Now insert the terms, which may result
					//in the child being removed from the document
					insertRadLexTerms(child);
					child = next;
				}
			}
		}
		else if (type == Node.TEXT_NODE) {
			Document doc = node.getOwnerDocument();
			Node parent = node.getParentNode();

			//Replace multiple whitespace characters with a single space.
			String text = node.getNodeValue().replaceAll("\\s+"," ");
			node.setNodeValue(text);

			Result result;
			while ((result=getFirstTerm(node)) != null) {
				//Okay, here is the situation. We have found a string
				//in the text node that matches a RadLex term.
				//We have to split the text node twice, once before
				//the term and once after it. The text node in the
				//middle will be the one containing the term. We must
				//then replace that node with a term element containing
				//the term text node.

				//First split the text node before the term.
				//The "node" variable will refer to the text node
				//containing the text before the term.
				//The "termText" variable will refer to the text node
				//containing the term text and the text (if any) after it
				Text termText = ((Text)node).splitText(result.start);

				//Now split the termText node after the term.
				//The "remainingText" variable will refer to the text node
				//containing all the text after the term.
				Text remainingText = termText.splitText(result.length);

				//Now we have to wrap the termText node in a term element.
				Element termElement = doc.createElement("term");
				termElement.setAttribute("lexicon", "RadLex");
				termElement.setAttribute("id", result.uid);
				parent.insertBefore(termElement, termText);
				//Note: the appendChild method removes the
				//appended node if it is already in the document,
				//and then appends it in the desired place.
				termElement.appendChild(termText);

				//Okay, we have processed this term, so all we have
				//to do is set up to check the remainingText node.
				node = remainingText;
			}
		}
	}

	static class Result {
		int start = 0;
		int length = 0;
		String uid = "";
		public Result(int start, int length, String uid) {
			this.start = start;
			this.length = length;
			this.uid = uid;
		}
	}

	static class Word {
		String text;
		int start;
		public Word(String text, int start) {
			this.text = text;
			this.start = start;
		}
	}

	private static Result getFirstTerm(Node node) {
		if (node.getNodeType() != Node.TEXT_NODE) return null;
		try {
			String text = node.getNodeValue();
			int k = 0;
			Word word;
			while ((word=getNextWord(text,k)) != null) {
				Term[] terms = RadLexIndex.getTerms(word.text);
				if (terms != null) {
					for (int i=0; i<terms.length; i++) {
						Term term = terms[i];
						//Note: only accept matching terms at least 5 characters long.
						if (term.matches(text, word.start) && (term.text.length() >= 5)) {
							return new Result(word.start, term.text.length(), term.id);
						}
					}
				}
				k = word.start + word.text.length();
			}
		}
		catch (Exception ex) { }
		return null;
	}

	private static Word getNextWord(String text, int k) {
		if (k < 0) return null;
		//Find the first letter character.
		while ((k < text.length()) && !Character.isLetter(text.charAt(k))) k++;
		//See if we ran off the end.
		if (k == text.length()) return null;
		//Find the end of the word (look for the next non-letter).
		int kk = k;
		while ((kk < text.length()) && Character.isLetter(text.charAt(kk))) kk++;
		//Return the word
		return new Word(text.substring(k, kk), k);
	}

	static class SkipElements extends HashSet<String> {
		public SkipElements() {
			super();
			this.add("title");
			this.add("alternative-title");
			this.add("author");
			this.add("patient");
			this.add("authorization");
			this.add("level");
			this.add("access");
			this.add("category");
			this.add("image-section");
			this.add("references");
			this.add("a");
			this.add("href");
			this.add("image");
			this.add("show");
			this.add("phi");
			this.add("document-type");
			this.add("document-id");
			this.add("creator");
			this.add("peer-review");
			this.add("publication-date");
			this.add("revision-history");
			this.add("rights");
			this.add("block");
			this.add("insert-image");
			this.add("insert-megasave");
			this.add("insert-dataset");
			this.add("metadata-refs");
			this.add("quiz");
		}
	}

	//*********************************************************************************************
	//
	//	Process a node tree and insert content from a DicomObject
	//
	//*********************************************************************************************

	//Walk a tree from a specified element and return true if
	//a DICOM tag element is found (<g...e.../>), indicating
	//that no DicomObjects have yet been inserted in the document.
	private static boolean checkTree(Element element) {
		if (dicomTag(element)) return true;
		NodeList nl = element.getChildNodes();
		Node node;
		for (int i=0; i<nl.getLength(); i++) {
			node = nl.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				if (checkTree((Element)node)) return true;
			}
		}
		return false;
	}

	//Walk the tree from a specified element,
	//inserting data from a DicomObject where required.
	private static void processElement(Element element, DicomObject dicomObject) {

		if (dicomTag(element)) {
			String text = getDicomElementText(element, dicomObject).trim();
			boolean rc = element.getAttribute("requireContent").trim().toLowerCase().equals("yes");
			if (!rc || !text.equals("")) {
				Node node = element.getOwnerDocument().createTextNode(text);
				element.getParentNode().replaceChild( node, element );
			}
		}

		else if (element.getTagName().equals("block")) {
			Node clone = element.getOwnerDocument().importNode( element.cloneNode(true), true );
			Node child;
			Element elem;
			Node parent = element.getParentNode();
			NodeList nl = clone.getChildNodes();
			for (int i=0; i<nl.getLength(); i++ ) {
				child = nl.item(i);
				if (child.getNodeType() == Node.ELEMENT_NODE) {
					processElement( (Element)child, dicomObject );
				}
				parent.insertBefore( child, element );
			}
		}

		else if (element.getTagName().equals("table")) {
			Node node;
			Element elem;
			NodeList nodeList = element.getChildNodes();
			for (int i=0; i<nodeList.getLength(); i++) {
				node = nodeList.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					elem = (Element)node;
					if (dicomTag(elem)) {
						Document doc = element.getOwnerDocument();
						Element tr = doc.createElement("tr");
						Element td = doc.createElement("td");
						td.setAttribute("width", "200");
						td.setTextContent(getDicomElementName(elem));
						tr.appendChild(td);
						td = doc.createElement("td");
						td.setTextContent(getDicomElementText(elem, dicomObject));
						tr.appendChild(td);
						elem.getParentNode().replaceChild(tr, elem);
					}
					else processElement(elem, dicomObject);
				}
			}
		}

		else if (element.getTagName().equals("publication-date")) {
			if (element.getTextContent().trim().equals("")) {
				element.setTextContent(StringUtil.getDate("-"));
			}
		}

		else {
			//Process the attributes
			NamedNodeMap attributes = element.getAttributes();
			Attr attr;
			for (int i=0; i<attributes.getLength(); i++) {
				attr = (Attr)attributes.item(i);
				String attrName = attr.getName();;
				String attrValue = attr.getValue().trim();
				if (dicomTag(attrValue)) {
					attrValue = getDicomElementText(attrValue, dicomObject);
					element.setAttribute(attrName, attrValue);
				}
			}
			//Now process the child elements (skipping all other node types)
			Node node;
			NodeList nodeList = element.getChildNodes();
			for (int i=0; i<nodeList.getLength(); i++) {
				node = nodeList.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					processElement( (Element)node, dicomObject );
				}
			}
		}
	}

	//Determine whether the argument is a
	//DICOM tag instruction, calling for the insertion
	//of the value of the element. The DICOM tag instruction
	//has the form <GxxxxEyyyy>. There can optionally be
	//attributes, but they are ignored. The name is not
	//case-sensitive.
	private static boolean dicomTag(Element element) {
		return element.getTagName().matches("[gG][0-9a-fA-F]{4}[eE][0-9a-fA-F]{4}.*");
	}

	//The same method for checking an attribute value for
	//the form: @GxxxxEyyyy
	private static boolean dicomTag(String name) {
		return name.matches("@[gG][0-9a-fA-F]{4}[eE][0-9a-fA-F]{4}.*");
	}

	//Create the tag int from the name of the
	//DICOM tag instruction.
	private static int getTag(Element element) {
		if (dicomTag(element)) {
			String s = element.getTagName();
			s = s.substring(1,5) + s.substring(6,10);
			try { return Integer.parseInt(s,16); }
			catch (Exception e) { }
		}
		return -1;
	}

	//Create the tag int from an attribute value as described in the
	//dicomTag(String) function..
	private static int getTag(String name) {
		if (dicomTag(name)) {
			name = name.substring(2,6) + name.substring(7,11);
			try { return Integer.parseInt(name,16); }
			catch (Exception e) { }
		}
		return -1;
	}

	//Get the dcm4che name of a DICOM element,
	//given the DICOM tag instruction element.
	private static String getDicomElementName(Element element) {
		int tag = getTag(element);
		if (tag == -1) return "UNKNOWN DICOM ELEMENT";
		String tagName = DicomObject.getElementName(tag);
		if (tagName != null) return tagName;
		tagName = element.getAttribute("desc");
		if (!tagName.equals("")) return tagName;
		return "UNKNOWN DICOM ELEMENT";
	}

	//Get the text value of a DICOM element in the
	//DicomObject, given the DICOM tag instruction.
	private static String getDicomElementText(Element element, DicomObject dicomObject) {
		int tag = getTag(element);
		return getDicomElementText(tag, dicomObject);
	}

	//The same function for getting the element from an attribute value
	//as determined by the dicomTag(String) function.
	private static String getDicomElementText(String name, DicomObject dicomObject) {
		int tag = getTag(name);
		return getDicomElementText(tag, dicomObject);
	}

	private static String getDicomElementText(int tag, DicomObject dicomObject) {
		if (tag == -1) return "UNKNOWN DICOM ELEMENT";
		try {
			String value = dicomObject.getElementValue(tag);
			if (value != null) return XmlUtil.escapeChars(value);
			return "";
		}
		catch (Exception e) { };
		return "value missing";
	}

}
