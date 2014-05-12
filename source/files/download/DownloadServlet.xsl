<?xml version="1.0" encoding="iso-8859-1"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:output method="xml" encoding="utf-8" omit-xml-declaration="yes" />

<xsl:param name="ui"/>
<xsl:param name="upload"/>
<xsl:param name="admin"/>

<xsl:template match="/files">
	<html>
		<head>
			<title>Download Software</title>
			<link rel="Stylesheet" type="text/css" media="all" href="/download/DownloadServlet.css"></link>
		</head>
		<body>
			<xsl:if test="$ui='classic'">
				<div class="closebox">
					<img src="/icons/home.png"
						 onclick="window.open('/','_self');"
						 title="Go to the server home page"/>
				</div>
			</xsl:if>

			<h1>Download Software</h1>

			<center>
				<p class="note">
					The tables below provide access to teaching file and clinical trial tools developed under the RSNA MIRC project. 
				</p>

				<h3>CTP and TFS</h3>

				<p class="note">
					CTP is the MIRC Clinical Trials Processor. CTP provides the processing capabilities required for sites to participate in multisite clinical trials in a
					 highly configurable and extensible application. Installation and configuration instructions for CTP are available on the MIRC Wiki at 
					 <a href="http://mircwiki.rsna.org/index.php?title=CTP-The_RSNA_Clinical_Trial_Processor" target="_blank">
					 	http://mircwiki.rsna.org/index.php?title=CTP-The_RSNA_Clinical_Trial_Processor
					 </a>.
				</p>

				<p class="note">
					TFS is the MIRC Teaching File System. TFS gives users the ability to author, manage, store and share radiology teaching files locally or across 
					institutions. It can be installed on a laptop to use for personal teaching files or on a departmental server to provide an institutional teaching file 
					system. Further information and instructions are available at 
					<a href="http://www.rsna.org/tfs.aspx" target="_blank">http://www.rsna.org/tfs.aspx</a>. 
				</p>				

				<table border="1">
					<tr>
						<th>File</th>
						<th>Description</th>
						<th class="size">Size</th>
						<th>Version</th>
					</tr>
					<xsl:for-each select="file">
						<xsl:if test="@name = 'CTP-installer.jar' or @name = 'TFS-installer.jar'">
							<tr>
								<td>
									<a href="/download/{@name}"><xsl:value-of select="@name"/></a>
								</td>
								<td>
									<xsl:if test="@desc">
										<xsl:value-of select="@desc"/>
									</xsl:if>
									<xsl:if test="not(@desc)">&#160;</xsl:if>
								</td>
								<td class="size"><xsl:value-of select="format-number(@size,'#,##0')"/></td>
								<td>
									<xsl:choose>
										<xsl:when test="string-length(normalize-space(@build)) != 0">
											<xsl:value-of select="@build"/>
										</xsl:when>
										<xsl:otherwise>
											<xsl:value-of select="@lastModified"/>
										</xsl:otherwise>
									</xsl:choose>
								</td>
							</tr>
						</xsl:if>
					</xsl:for-each>
				</table>

				<h3>Other Tools and Utilities</h3>

				<p class="note">
					The programs linked in the table below provide useful tools for users of CTP and TFS, and general utilities for managing imaging data. Articles on the 
					purpose and use of many of these programs is available on the MIRC Wiki 
					(<a href="http://mircwiki.rsna.org" target="_blank">http://mircwiki.rsna.org</a>).
				</p>

				<table border="1">
					<tr>
						<th>File</th>
						<th>Description</th>
						<th class="size">Size</th>
						<th>Version</th>
					</tr>
					<xsl:for-each select="file">
						<xsl:if test="not(@name = 'CTP-installer.jar') and not(@name = 'TFS-installer.jar')">
							<tr>
								<td>
									<a href="/download/{@name}"><xsl:value-of select="@name"/></a>
								</td>
								<td>
									<xsl:if test="@desc">
										<xsl:value-of select="@desc"/>
									</xsl:if>
									<xsl:if test="not(@desc)">&#160;</xsl:if>
								</td>
								<td class="size"><xsl:value-of select="format-number(@size,'#,##0')"/></td>
								<td>
									<xsl:choose>
										<xsl:when test="string-length(normalize-space(@build)) != 0">
											<xsl:value-of select="@build"/>
										</xsl:when>
										<xsl:otherwise>
											<xsl:value-of select="@lastModified"/>
										</xsl:otherwise>
									</xsl:choose>
								</td>
							</tr>
						</xsl:if>
					</xsl:for-each>
				</table>

				<xsl:variable name="footerlinks" select="($upload='yes') or ($admin='yes')"/>
				<xsl:if test="$footerlinks">
					<br/>
					<p class="center">
						<xsl:if test="$upload='yes'">
							<a href="/download/upload?ui={$ui}">Upload a file</a>
							<br/>
						</xsl:if>
						<xsl:if test="($admin='yes') or ($upload='yes')">
							<a href="/download/report" target="report">Report</a>
						</xsl:if>
					</p>
				</xsl:if>
			</center>

		</body>
	</html>
</xsl:template>

</xsl:stylesheet>
