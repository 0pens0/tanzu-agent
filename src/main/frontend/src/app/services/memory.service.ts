import { Injectable, signal } from '@angular/core';

export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface HistoryMessage {
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface MemoryFact {
  key: string;
  value: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class MemoryService {
  private readonly apiUrl = '/api/memory';

  readonly conversations = signal<ConversationSummary[]>([]);
  readonly facts = signal<MemoryFact[]>([]);
  readonly memoryAvailable = signal(false);
  readonly contextLoaded = signal(false);

  async loadConversations(): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/conversations`);
      if (!response.ok) {
        this.memoryAvailable.set(false);
        return;
      }
      const data: ConversationSummary[] = await response.json();
      this.conversations.set(data);
      this.memoryAvailable.set(true);
    } catch {
      this.memoryAvailable.set(false);
    }
  }

  async loadFacts(): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/facts`);
      if (!response.ok) return;
      const data: MemoryFact[] = await response.json();
      this.facts.set(data);
    } catch {
      // memory profile not active — ignore
    }
  }

  async deleteFact(key: string): Promise<void> {
    try {
      await fetch(`${this.apiUrl}/facts/${encodeURIComponent(key)}`, { method: 'DELETE' });
      this.facts.update(list => list.filter(f => f.key !== key));
    } catch {
      // ignore
    }
  }

  async getMessages(sessionId: string): Promise<HistoryMessage[]> {
    try {
      const response = await fetch(`${this.apiUrl}/conversations/${sessionId}`);
      if (!response.ok) return [];
      return await response.json();
    } catch {
      return [];
    }
  }

  setContextLoaded(loaded: boolean): void {
    this.contextLoaded.set(loaded);
  }

  formatRelativeTime(isoString: string): string {
    const date = new Date(isoString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60_000);
    const diffHrs = Math.floor(diffMin / 60);
    const diffDays = Math.floor(diffHrs / 24);

    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    if (diffHrs < 24) return `${diffHrs}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;
    return date.toLocaleDateString();
  }
}
