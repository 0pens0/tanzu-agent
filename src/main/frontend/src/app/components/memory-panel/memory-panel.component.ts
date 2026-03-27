import {
  Component, input, output, computed, signal
} from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MemoryService, MemoryFact, ConversationSummary, HistoryMessage } from '../../services/memory.service';

@Component({
  selector: 'app-memory-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTabsModule,
    MatTooltipModule,
    MatDividerModule,
    MatChipsModule
  ],
  templateUrl: './memory-panel.component.html',
  styleUrl: './memory-panel.component.scss'
})
export class MemoryPanelComponent {
  readonly collapsed   = input<boolean>(false);
  readonly collapseToggle = output<void>();

  protected selectedConversation = signal<ConversationSummary | null>(null);
  protected historyMessages = signal<HistoryMessage[]>([]);

  protected facts       = computed(() => this.memoryService.facts());
  protected conversations = computed(() => this.memoryService.conversations());
  protected contextLoaded = computed(() => this.memoryService.contextLoaded());

  constructor(private memoryService: MemoryService) {}

  protected toggle(): void {
    this.collapseToggle.emit();
    if (!this.collapsed()) {
      this.memoryService.loadFacts();
      this.memoryService.loadConversations();
    }
  }

  protected refreshFacts(): void {
    this.memoryService.loadFacts();
  }

  protected deleteFact(key: string): void {
    this.memoryService.deleteFact(key);
  }

  protected async viewConversation(conv: ConversationSummary): Promise<void> {
    this.selectedConversation.set(conv);
    const msgs = await this.memoryService.getMessages(conv.id);
    this.historyMessages.set(msgs);
  }

  protected clearConversationView(): void {
    this.selectedConversation.set(null);
    this.historyMessages.set([]);
  }

  protected relativeTime(iso: string): string {
    return this.memoryService.formatRelativeTime(iso);
  }

  protected truncate(text: string, max = 120): string {
    return text.length > max ? text.slice(0, max) + '…' : text;
  }
}
