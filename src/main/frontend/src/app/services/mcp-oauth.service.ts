import { Injectable, signal } from '@angular/core';

/**
 * Service for broker-based credential management.
 *
 * Detects whether the Agent Credential Broker is configured and exposes
 * the broker URL so UI components can link users to the broker grants page.
 */
@Injectable({
  providedIn: 'root'
})
export class McpOAuthService {
  private _brokerUrl = signal<string | null>(null);
  readonly brokerUrl = this._brokerUrl.asReadonly();

  /**
   * Check if the broker is configured and set the broker URL.
   */
  async checkBrokerConfig(): Promise<void> {
    try {
      const response = await fetch('/api/broker/status');
      if (response.ok) {
        const data = await response.json();
        if (data.brokerUrl) {
          this._brokerUrl.set(data.brokerUrl);
        }
      }
    } catch {
      // Broker not configured
    }
  }

  /**
   * Whether broker integration is active.
   */
  get isBrokerMode(): boolean {
    return this._brokerUrl() !== null;
  }

  /**
   * Open the broker UI grants page in a new tab.
   */
  openBrokerGrants(): void {
    const url = this._brokerUrl();
    if (url) {
      window.open(url + '/grants', '_blank');
    }
  }
}
