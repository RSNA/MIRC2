function loaded() {
	var tools = new Array();
	tools[tools.length] = new PopupTool("/icons/save.png", "Save", null, save);
	tools[tools.length] = new PopupTool("/icons/home.png", "Return to the home page", "/query", null);
	setPopupToolPanel( tools );
}
window.onload = loaded;

function save() {
	var form = document.getElementById("formID");
	form.target = "_self";
	form.submit();
}
