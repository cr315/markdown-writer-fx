/*
 * Copyright (c) 2017 Karl Tauber <karl at jformdesigner dot com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  o Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  o Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.markdownwriterfx.editor;

import java.util.ArrayList;
import java.util.Collections;
import javafx.scene.input.KeyEvent;
import com.vladsch.flexmark.ast.Block;
import com.vladsch.flexmark.ast.DelimitedNode;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.ast.NodeVisitor;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.util.Pair;

/**
 * Smart Markdown text formatting methods.
 *
 * @author Karl Tauber
 */
class SmartFormat
{
	private static final int WRAP_LENGTH = 80;

	private final MarkdownEditorPane editor;
	private final MarkdownTextArea textArea;

	SmartFormat(MarkdownEditorPane editor, MarkdownTextArea textArea) {
		this.editor = editor;
		this.textArea = textArea;
	}

	void format(KeyEvent e) {
		Node markdownAST = editor.getMarkdownAST();
		if (markdownAST == null)
			return;

		int wrapLength = WRAP_LENGTH;

		// find and format paragraphs
		ArrayList<Pair<Paragraph, String>> formattedParagraphs = new ArrayList<>();
		NodeVisitor visitor = new NodeVisitor(Collections.emptyList()) {
			@Override
			public void visit(Node node) {
				if (node instanceof Paragraph) {
					Paragraph paragraph = (Paragraph) node;
					int indent = computeIndent(paragraph);
					String newText = formatParagraph(paragraph, wrapLength, indent);
					if (!paragraph.getChars().equals(newText, false))
						formattedParagraphs.add(new Pair<>(paragraph, newText));
				} else
					visitChildren(node);
			}
		};
		visitor.visit(markdownAST);

		// replace text of formatted paragraphs
		CompoundChange.run(textArea, changer -> {
			for (int i = formattedParagraphs.size() - 1; i >= 0; i--) {
				Pair<Paragraph, String> pair = formattedParagraphs.get(i);
				Paragraph paragraph = pair.getFirst();
				String newText = pair.getSecond();

				int startOffset = paragraph.getStartOffset();
				int endOffset = paragraph.getEndOffset();
				if (paragraph.getChars().endsWith("\n"))
					endOffset--;

				changer.replaceText(startOffset, endOffset, newText);
			}
		} );

		SmartEdit.selectRange(textArea, 0, 0);
	}

	private int computeIndent(Paragraph paragraph) {
		Block parent = paragraph.getParent();
		if (parent instanceof ListItem) {
			int paraStartOffset = paragraph.getStartOffset();
			int paraLineStartOffset = paragraph.getDocument().getChars().startOfLine(paraStartOffset);
			return paraStartOffset - paraLineStartOffset;
		} else
			return 0;
	}

	private String formatParagraph(Paragraph paragraph, int wrapLength, int indent) {
		// collect the paragraph text
		StringBuilder buf = new StringBuilder(paragraph.getTextLength());
		collectFormattableText(buf, paragraph);

		// format the paragraph text
		return formatText(buf.toString(), wrapLength, indent);
	}

	/**
	 * Collects the text of a single paragraph.
	 *
	 * Replaces:
	 *   - tabs with spaces
	 *   - newlines with spaces (may occur in Code nodes)
	 *   - soft line breaks with spaces
	 *   - hard line breaks with special marker characters
	 *   - spaces and tabs in special nodes, that should not formatted, with marker characters
	 */
	private void collectFormattableText(StringBuilder buf, Node node) {
		for (Node n = node.getFirstChild(); n != null; n = n.getNext()) {
			if (n instanceof Text)
				buf.append(n.getChars().toString().replace('\t', ' ').replace('\n', ' '));
			else if (n instanceof DelimitedNode) {
				// italic, bold and code
				buf.append(((DelimitedNode) n).getOpeningMarker());
				collectFormattableText(buf, n);
				buf.append(((DelimitedNode) n).getClosingMarker());
			} else if (n instanceof SoftLineBreak)
				buf.append(' ');
			else if (n instanceof HardLineBreak)
				buf.append(n.getChars().startsWith("\\") ? " \4 " : " \3 ");
			else {
				// other text that should be not wrapped or formatted
				buf.append(protectWhitespace(n.getChars().toString()));
			}
		}
	}

	/**
	 * Formats the given text by merging multiple spaces into one space
	 * and wrapping lines.
	 */
	private String formatText(String text, int wrapLength, int indent) {
		String[] words = text.split(" +");

		StringBuilder buf = new StringBuilder(text.length());
		int lineLength = indent;
		for (String word : words) {
			if (word.equals("\3") || word.equals("\4")) {
				// hard line break ("two spaces" or "backslash")
				buf.append(word.equals("\3") ? "  \n" : "\\\n");
				lineLength = 0;
				continue;
			}

			if (lineLength > indent && lineLength + 1 + word.length() > wrapLength) {
				// wrap
				buf.append('\n');
				lineLength = 0;

				// indent
				if (indent > 0) {
					for (int i = 0; i < indent; i++)
						buf.append(' ');
					lineLength += indent;
				}
			} else if (lineLength > indent) {
				// add space before word
				buf.append(' ');
				lineLength++;
			}

			// add word
			buf.append(word);
			lineLength += word.length();
		}

		return unprotectWhitespace(buf.toString());
	}

	private String protectWhitespace(String s) {
		return s.replace(' ', '\1').replace('\t', '\2');
	}

	private String unprotectWhitespace(String s) {
		return s.replace('\1', ' ').replace('\2', '\t');
	}
}