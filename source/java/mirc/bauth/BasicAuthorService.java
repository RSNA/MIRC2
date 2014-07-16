/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package mirc.bauth;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.zip.ZipEntry;

import mirc.activity.ActivityDB;
import mirc.MircConfig;
import mirc.prefs.Preferences;
import mirc.storage.Index;
import mirc.util.MircDocument;
import mirc.util.MircImage;

import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.server.Path;
import org.rsna.server.User;
import org.rsna.server.Users;
import org.rsna.multipart.UploadedFile;
import org.rsna.servlets.Servlet;

import org.apache.log4j.Logger;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.objects.ZipObject;

import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.XmlUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The Basic Author Service.
 * <p>
 * This service accepts multipart/form-data submissions containing
 * document elements and files for inclusion in new MIRCdocuments.
 */
public class BasicAuthorService extends Servlet {

	static final Logger logger = Logger.getLogger(BasicAuthorService.class);

	static String textext = ".txt";
	static String[] textExtensions = textext.split(",");

	/**
	 * Static init method to initialize the static variables.
	 * This method is called by the ServletSelector when the
	 * servlet is added to the list of servlets by the MIRC
	 * plugin. At that point, the MircConfig instance has
	 * been created and is available.
	 */
	public static void init(File root, String context) {
		File mircRoot = MircConfig.getInstance().getRootDirectory();

		//Make sure the default templates are in place
		File bauth = new File( mircRoot, "bauth" );

		File abrTemplate = new File( bauth, "example-abr-template.xml" );
		FileUtil.getFile( abrTemplate, "/bauth/example-abr-template.xml" );

		File basicTemplate = new File( bauth, "example-basic-template.xml" );
		FileUtil.getFile( basicTemplate, "/bauth/example-basic-template.xml" );
	}

	/**
	 * Construct a BasicAuthorService servlet.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public BasicAuthorService(File root, String context) {
		super(root, context);
		//Override the root supplied by the HttpServer
		//with the root of the MIRC plugin.
		this.root = MircConfig.getInstance().getRootDirectory();
	}

	/**
	 * Get a web page containing a submission form.
	 */
	public void doGet(HttpRequest req, HttpResponse res) throws Exception {

		//Only accept connections from authors
		if (!req.userHasRole("author")) { res.redirect("/query"); return; }

		Path path = req.getParsedPath();
		String ssid = path.element(1);
		if (ssid.startsWith("ss")) {

			MircConfig mc = MircConfig.getInstance();

			//Get an enabled local library in which to store the document.
			Element lib = mc.getEnabledLocalLibrary(ssid, "authenb");
			if (lib != null) {
				ssid = lib.getAttribute("id"); //get the ID for the library we found.

				//Get the library name
				Element titleElement = XmlUtil.getFirstNamedChild(lib, "title");
				String title = titleElement.getTextContent();

				//Get the user and his preferences
				String username = req.getUser().getUsername();
				Element prefs = Preferences.getInstance().get( username, true );

				//Get the UI to determine whether to include the home icon.
				String ui = req.getParameter("ui", "");
				if (!ui.equals("integrated")) ui = "classic";

				//Get the template
				String templateName = req.getParameter("file", "example-basic-template.xml").trim();
				File bauth = new File(root, "bauth");
				File template = new File(bauth, templateName);
				FileUtil.getFile( template, "/bauth/"+templateName );
				Document templateXML = XmlUtil.getDocument(template);

				//Generate the submission page.
				Object[] params = {
					"ui",			ui,
					"ssid",			ssid,
					"libraryTitle",	title,
					"prefs",		prefs,
					"templates",	getTemplates(bauth),
					"textext",		".txt",
					"options",		mc.enumeratedValues,
					"config",		mc.getXML()
				};
				Document xsl = XmlUtil.getDocument( FileUtil.getStream( "/bauth/BasicAuthorService.xsl" ) );
				res.write( XmlUtil.getTransformedText( templateXML, xsl, params ) );
				res.setContentType("html");
				res.send();
			}
			else res.redirect("/query");
		}
		else super.doGet(req, res);
	}

	//Get an XML document containing a list of all the
	//templates available for this service.
	private Document getTemplates(File dir) {
		Document doc;
		try { doc= XmlUtil.getDocument(); }
		catch (Exception failure) { return null; }
		Element root = doc.createElement("templates");
		doc.appendChild(root);
		File[] files = dir.listFiles();
		for (int i=0; i<files.length; i++) {
			if (files[i].getName().toLowerCase().endsWith(".xml")) {
				try {
					Document md = XmlUtil.getDocument(files[i]);
					Element mdRoot = md.getDocumentElement();
					if (mdRoot.getTagName().equals("MIRCdocument")) {
						String title = "Untitled";
						NodeList nl = mdRoot.getElementsByTagName("title");
						if (nl.getLength() > 0) title = nl.item(0).getTextContent().replaceAll("\\s+", " ");
						Element child = doc.createElement("template");
						root.appendChild(child);
						Element childFile = doc.createElement("file");
						child.appendChild(childFile);
						childFile.setTextContent(files[i].getName());
						Element childTitle = doc.createElement("title");
						child.appendChild(childTitle);
						childTitle.setTextContent(title);
					}
				}
				catch (Exception ignoreFile) { }
			}
		}
		return doc;
	}

	/**
	 * The servlet method that responds to an HTTP POST.
	 */
	public void doPost(HttpRequest req, HttpResponse res) throws Exception {

		//Only accept connections from authors
		if (!req.userHasRole("author")) { res.redirect("/query"); return; }

		Path path = req.getParsedPath();
		String ssid = path.element(1);
		if (ssid.startsWith("ss")) {

			logger.debug("POST received for "+req.path);

			//Make sure the author service is enabled
			MircConfig mc = MircConfig.getInstance();
			Element lib = mc.getLocalLibrary(ssid);
			boolean enabled = ((lib != null) && lib.getAttribute("authenb").equals("yes"));
			if (enabled) {

				//Make a temporary directory to receive the files
				File dir = mc.createTempDirectory();
				logger.debug("...created tempDirectory: "+dir);

				//Get the posted files
				int maxsize = StringUtil.getInt( lib.getAttribute("maxsize"), 0 );
				if (maxsize == 0) maxsize = 75;
				maxsize *= 1024*1024; //make it megabytes
				LinkedList<UploadedFile> files = req.getParts(dir, maxsize);
				logger.debug("...received "+files.size()+" files");

				//Get the requested UI for the page
				String ui = req.getParameter("ui", "classic");

				//Make a File that points to the MIRCdocument.xml file to be created,
				//and copy the template into that file.
				String templateName = req.getParameter("templatename");
				File bauth = new File(root, "bauth");
				File template = new File(bauth, templateName);
				File mdFile = new File(dir, "MIRCdocument.xml");
				FileUtil.copy(template, mdFile);

				//Parse the document and insert the form parameters.
				Document mdXML = XmlUtil.getDocument(mdFile);
				Element root = mdXML.getDocumentElement();

				String username = req.getUser().getUsername();
				String name = req.getParameter("name");
				String affiliation = req.getParameter("affiliation");
				String contact = req.getParameter("contact");

				setElement(root,"title", req.getParameter("title"));
				setElement(root,"author/name", name);
				setElement(root,"author/affiliation", affiliation);
				setElement(root,"author/contact", contact);
				setElement(root,"abstract", req.getParameter("abstract-text"));
				setElement(root,"authorization/owner", req.getParameter("username"));
				setElement(root,"category", req.getParameter("category"));


				//******************************************
				//This code was removed intentionally to
				//allow admins to preload all  permissions
				//******************************************
				//Set the read and update privileges.
				//In this service, the read and update privileges
				//are granted only to the owner.
				//setElement(root,"authorization/read", "");
				//setElement(root,"authorization/update", "");

				//Insert the section elements
				Node child = root.getFirstChild();
				while (child != null) {
					if ((child.getNodeType() == Node.ELEMENT_NODE) && child.getNodeName().equals("section")) {
						Node x = child.getFirstChild();
						while (x != null) {
							Node next = x.getNextSibling();
							if (x.getNodeType() == Node.ELEMENT_NODE) {
								String nodeName = x.getNodeName();
								if (nodeName.equals("p")) processParagraph(req, (Element)x);
								else if (nodeName.equals("textblock")) processTextblock(req, (Element)x);
							}
							x = next;
						}
					}
					child = child.getNextSibling();
				}

				//Force a publication-date element containing today's date.
				insertPublicationDate(root);

				//See if DicomObjects are to be anonymized
				boolean anonymize = req.hasParameter("anonymize");

				//Save the file and then insert all the files.
				logger.debug("...about to insert the files");
				FileUtil.setText(mdFile, XmlUtil.toString(mdXML));
				MircDocument md = insertFiles(mdFile, files, anonymize);

				//Now move the directory into the documents tree.
				Index index = Index.getInstance(ssid);
				File docs = index.getDocumentsDir();
				File storageDir = new File(docs, StringUtil.makeNameFromDate());
				FileUtil.createParentDirectory(storageDir);
				File parentDir = storageDir.getParentFile();
				if (!parentDir.exists()) logger.debug("...parentDir could not be created: "+parentDir);

				logger.debug("...about to rename the document to "+storageDir);
				if (!dir.renameTo(storageDir)) {
					logger.warn("...rename FAILED");
					if (storageDir.exists()) {
						for (File f : storageDir.listFiles()) logger.debug("......"+f.getName());
					}
					FileUtil.deleteAll(dir);
					throw new Exception("Unable to install the MIRCdocument.");
				}

				//Index the MIRC document
				String key = index.getKey(new File(storageDir, "MIRCdocument.xml"));
				logger.debug("...about to index the document: "+key);
				index.insertDocument(key);

				//Record the activity
				ActivityDB.getInstance().increment(ssid, "bauth", username);

				//Success, save the author information in the preferences
				logger.debug("...about to set the preferences for "+username+" ("+name+")");
				Preferences.getInstance().setAuthorInfo(username, name, affiliation, contact);

				//Redirect to the document
				String redirectPath = "/storage/"+ssid+"/"+key;
				logger.debug("...about to redirect to "+redirectPath);
				res.redirect(redirectPath);
				logger.debug("...done");
			}
			else res.redirect("/query");
		}
		else super.doGet(req, res);
	}

	//Process the contents of a paragraph element, inserting
	//content for select, ul, ol, and textblock  elements.
	private void processParagraph(HttpRequest req, Element p) throws Exception {
		Node child = p.getFirstChild();
		while (child != null) {
			Node nextSibling = child.getNextSibling();
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				Element x = (Element)child;
				String tagName = x.getTagName();
				if (tagName.equals("select")) processSelect(req, x);
				else if (tagName.equals("ul")) processList(req, x);
				else if (tagName.equals("ol")) processList(req, x);
				else if (tagName.equals("textblock")) processTextblock(req, x);
			}
			child = nextSibling;
		}
	}

	//Process the contents of a select element,
	//removing the select element and inserting the
	//value in its place.
	private void processSelect(HttpRequest req, Element select) throws Exception {
		String name = select.getAttribute("name");
		String value = req.getParameter(name);
		Node parent = select.getParentNode();
		Node text = parent.getOwnerDocument().createTextNode(value);
		parent.replaceChild(text, select);
	}

	//Process the contents of a list element,
	//inserting the selected value.
	private void processList(HttpRequest req, Element list) throws Exception {
		Node child = list.getFirstChild();
		while (child != null) {
			Node nextSibling = child.getNextSibling();
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				if (child.getNodeName().equals("checkbox")) processCheckbox(req, (Element)child);
			}
			child = nextSibling;
		}
	}

	//Process the contents of a textblock element,
	//inserting the contents of the element and
	//ensuring that the text is well-formed.
	private void processTextblock(HttpRequest req, Element textblock) throws Exception {
		try {
			//Get the value of the element from the form.
			String name = textblock.getAttribute("name");
			String value = req.getParameter(name+"-text");
			//Next create a temporary document for parsing the text.
			Document temp = XmlUtil.getDocument( "<root>"+value+"</root>" );
			//Finally, import all the child nodes of the root
			//of the temporary document into the template
			//right before the textblock element.
			Element root = temp.getDocumentElement();
			Document doc = textblock.getOwnerDocument();
			Node tp = textblock.getParentNode();
			while (root.hasChildNodes()) {
				Node child = root.getFirstChild();
				tp.insertBefore(doc.importNode(child,true), textblock);
				root.removeChild(child);
			}
			//Finally, remove the textblock element
			tp.removeChild(textblock);
		}
		catch (Exception ignore) { }
	}

	//Process the contents of a checkbox element,
	//inserting the selected value wrapped in an li element.
	private void processCheckbox(HttpRequest req, Element cb) throws Exception {
		String name = cb.getAttribute("name");
		String value = req.getParameter(name);
		Node parent = cb.getParentNode();
		if (value != null) {
			//The form just supplies a dummy value when the box is checked..
			//Use the value from the template, not the one from the form.
			value = cb.getTextContent();
			//Now replace the element with an LI.
			Node li = parent.getOwnerDocument().createElement("li");
			Node text = parent.getOwnerDocument().createTextNode(value);
			li.appendChild(text);
			parent.replaceChild(li, cb);
		}
		else {
			//The box was not checked; remove it from the document.
			parent.removeChild(cb);
		}
	}

	//Remove all publication-date elements and insert one with today's date.
	private void insertPublicationDate(Element root) {
		NodeList nl = root.getElementsByTagName("publication-date");
		for (int i=0; i<nl.getLength(); i++) {
			Node pd = nl.item(i);
			pd.getParentNode().removeChild(pd);
		}
		Document doc = root.getOwnerDocument();
		Element pd = doc.createElement("publication-date");
		pd.appendChild(doc.createTextNode(StringUtil.getDate("-")));
		root.appendChild(pd);
	}

	//Replace the contents of an element with XML text.
	private void setElement(Element root, String path, String text) {
		Element target = XmlUtil.getElementViaPath(root,"MIRCdocument/"+path);
		if ((target != null) && (text != null)) setElement(target, text);
	}

	private void setElement(Element target, String text) {
		try {
			//First remove all the target's children.
			while (target.hasChildNodes()) target.removeChild(target.getFirstChild());

			//Next create a temporary document for parsing the text.
			Document temp = XmlUtil.getDocument( "<root>"+text+"</root>" );

			//Finally, import all the child nodes of the root
			//of the temporary document into the target.
			Element root = temp.getDocumentElement();
			while (root.hasChildNodes()) {
				Node child = root.getFirstChild();
				target.appendChild(target.getOwnerDocument().importNode(child,true));
				root.removeChild(child);
			}
		}
		catch (Exception ignore) { }
	}

	private MircDocument insertFiles(File mdFile, LinkedList<UploadedFile> files, boolean anonymize) throws Exception {

		//Instantiate the MircDocument so we can add the files to it.
		MircDocument md = new MircDocument(mdFile);

		//Now add in all the files.
		for ( UploadedFile file: files.toArray( new UploadedFile[files.size()] ) ) {
			md.insertFile(file.getFile(), true, textExtensions, anonymize);
		}

		//Sort the image-section
		md.sortImageSection();

		//Save the modified document and return it.
		md.save();
		return md;
	}

}