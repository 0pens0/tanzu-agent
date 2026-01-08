import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';

@Pipe({
  name: 'markdown',
  standalone: true
})
export class MarkdownPipe implements PipeTransform {
  constructor(private sanitizer: DomSanitizer) {
    // Configure marked options for better rendering
    marked.setOptions({
      gfm: true // Use GitHub Flavored Markdown
    });
  }

  transform(value: string): SafeHtml {
    if (!value) {
      return '';
    }

    try {
      // Pre-process to fix bold markers with spaces inside
      // Transform "** text**" or "**text **" to "**text**"
      let processed = value.replace(/\*\*\s+/g, '**');
      processed = processed.replace(/\s+\*\*/g, '**');

      // Parse markdown to HTML
      const html = marked.parse(processed, { async: false }) as string;

      // Sanitize and return as SafeHtml
      return this.sanitizer.sanitize(1, html) || '';
    } catch (error) {
      console.error('Error parsing markdown:', error);
      return value;
    }
  }
}

